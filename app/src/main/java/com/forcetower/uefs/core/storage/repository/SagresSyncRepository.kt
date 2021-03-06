/*
 * Copyright (c) 2018.
 * João Paulo Sena <joaopaulo761@gmail.com>
 *
 * This file is part of the UNES Open Source Project.
 *
 * UNES is licensed under the MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.forcetower.uefs.core.storage.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import com.forcetower.sagres.SagresNavigator
import com.forcetower.sagres.database.model.SCalendar
import com.forcetower.sagres.database.model.SDiscipline
import com.forcetower.sagres.database.model.SDisciplineClassLocation
import com.forcetower.sagres.database.model.SDisciplineGroup
import com.forcetower.sagres.database.model.SDisciplineMissedClass
import com.forcetower.sagres.database.model.SGrade
import com.forcetower.sagres.database.model.SPerson
import com.forcetower.sagres.operation.BaseCallback
import com.forcetower.sagres.operation.Status
import com.forcetower.sagres.parsers.SagresBasicParser
import com.forcetower.uefs.AppExecutors
import com.forcetower.uefs.core.model.unes.Access
import com.forcetower.uefs.core.model.unes.CalendarItem
import com.forcetower.uefs.core.model.unes.ClassGroup
import com.forcetower.uefs.core.model.unes.Discipline
import com.forcetower.uefs.core.model.unes.Message
import com.forcetower.uefs.core.model.unes.NetworkType
import com.forcetower.uefs.core.model.unes.SagresFlags
import com.forcetower.uefs.core.model.unes.Semester
import com.forcetower.uefs.core.model.unes.SyncRegistry
import com.forcetower.uefs.core.model.unes.notify
import com.forcetower.uefs.core.storage.database.UDatabase
import com.forcetower.uefs.core.util.VersionUtils
import com.forcetower.uefs.service.NotificationCreator
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SagresSyncRepository @Inject constructor(
    private val context: Context,
    private val database: UDatabase,
    private val executors: AppExecutors,
    private val firebaseAuthRepository: FirebaseAuthRepository
) {

    @WorkerThread
    fun performSync(executor: String) {
        val registry = createRegistry(executor)
        val access = database.accessDao().getAccessDirect()
        access ?: Timber.d("Access is null, sync will not continue")
        if (access != null) {
            // Only one sync may be active at a time
            synchronized(S_LOCK) { execute(access, registry) }
        } else {
            registry.completed = true
            registry.error = -1
            registry.success = false
            registry.message = "Credenciais de acesso inválidas"
            registry.end = System.currentTimeMillis()
            database.syncRegistryDao().insert(registry)
        }
    }

    private fun createRegistry(executor: String): SyncRegistry {
        val connectivity = ContextCompat.getSystemService(context, ConnectivityManager::class.java)!!

        if (VersionUtils.isMarshmallow()) {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            return if (capabilities != null) {
                val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val network = if (wifi) {
                    val manager = context.getSystemService(WifiManager::class.java)
                    manager.connectionInfo.ssid
                } else {
                    val manager = context.getSystemService(TelephonyManager::class.java)
                    manager.simOperatorName
                }
                Timber.d("Is on Wifi? $wifi. Network name: $network")
                SyncRegistry(
                    executor = executor, network = network,
                    networkType = if (wifi) NetworkType.WIFI.ordinal else NetworkType.CELLULAR.ordinal
                )
            } else {
                SyncRegistry(executor = executor, network = "Invalid", networkType = NetworkType.OTHER.ordinal)
            }
        } else {
            val manager = ContextCompat.getSystemService(context, WifiManager::class.java)
            val info = manager?.connectionInfo
            return if (info == null) {
                val phone = ContextCompat.getSystemService(context, TelephonyManager::class.java)
                val operatorName = phone?.simOperatorName
                if (operatorName != null) {
                    SyncRegistry(executor = executor, network = operatorName, networkType = NetworkType.CELLULAR.ordinal)
                } else {
                    SyncRegistry(executor = executor, network = "Invalid", networkType = NetworkType.OTHER.ordinal)
                }
            } else {
                SyncRegistry(executor = executor, network = info.ssid, networkType = NetworkType.WIFI.ordinal)
            }
        }
    }

    @WorkerThread
    private fun execute(access: Access, registry: SyncRegistry) {
        val uid = database.syncRegistryDao().insert(registry)
        registry.uid = uid
        database.gradesDao().markAllNotified()
        database.messageDao().setAllNotified()
        val score = login(access)
        if (score == null) {
            registry.completed = true
            registry.error = -2
            registry.success = false
            registry.message = "Login falhou"
            registry.end = System.currentTimeMillis()
            database.syncRegistryDao().update(registry)
            return
        }

        val person = me(score)
        if (person == null) {
            registry.completed = true
            registry.error = -3
            registry.success = false
            registry.message = "Busca de usuário falhou no Sagres"
            registry.end = System.currentTimeMillis()
            database.syncRegistryDao().update(registry)
            return
        }

        executors.others().execute { firebaseAuthRepository.loginToFirebase(person, access) }

        var result = 0
        if (!messages(person.id)) result += 1 shl 1
        if (!semesters(person.id)) result += 1 shl 2
        if (!startPage()) result += 1 shl 3
        if (!grades()) result += 1 shl 4

        registry.completed = true
        registry.error = result
        registry.success = result == 0
        registry.message = "Deve-se consultar as flags de erro"
        registry.end = System.currentTimeMillis()
        database.syncRegistryDao().update(registry)
    }

    fun login(access: Access): Double? {
        val login = SagresNavigator.instance.login(access.username, access.password)
        when (login.status) {
            Status.SUCCESS -> {
                val score = SagresBasicParser.getScore(login.document)
                Timber.d("Login Completed. Score Parsed: $score")
                return score
            }
            else -> produceErrorMessage(login)
        }
        return null
    }

    private fun me(score: Double): SPerson? {
        val me = SagresNavigator.instance.me()
        when (me.status) {
            Status.SUCCESS -> {
                val person = me.person
                if (person != null) {
                    database.profileDao().insert(person, score)
                    Timber.d("Me completed. Person name: ${person.name}")
                    return person
                } else {
                    Timber.e("Page loaded but API returned invalid types")
                }
            }
            else -> produceErrorMessage(me)
        }
        return null
    }

    @WorkerThread
    private fun messages(userId: Long): Boolean {
        val messages = SagresNavigator.instance.messages(userId)
        return when (messages.status) {
            Status.SUCCESS -> {
                val values = messages.messages?.map { Message.fromMessage(it, false) } ?: emptyList()
                database.messageDao().insertIgnoring(values)
                messagesNotifications()
                Timber.d("Messages completed. Messages size is ${values.size}")
                true
            }
            else -> {
                produceErrorMessage(messages)
                false
            }
        }
    }

    @WorkerThread
    private fun semesters(userId: Long): Boolean {
        val semesters = SagresNavigator.instance.semesters(userId)
        return when (semesters.status) {
            Status.SUCCESS -> {
                val values = semesters.getSemesters().map { Semester.fromSagres(it) }
                database.semesterDao().insertIgnoring(values)
                Timber.d("Semesters Completed with: ${semesters.getSemesters()}")
                true
            }
            else -> {
                produceErrorMessage(semesters)
                false
            }
        }
    }

    @WorkerThread
    private fun startPage(): Boolean {
        val start = SagresNavigator.instance.startPage()
        return when (start.status) {
            Status.SUCCESS -> {
                defineCalendar(start.calendar)
                defineDisciplines(start.disciplines)
                defineDisciplineGroups(start.groups)
                defineSchedule(start.locations)
                defineDemand(start.isDemandOpen)

                Timber.d("Semesters: ${start.semesters}")
                Timber.d("Disciplines:  ${start.disciplines}")
                Timber.d("Calendar: ${start.calendar}")
                true
            }
            else -> {
                produceErrorMessage(start)
                false
            }
        }
    }

    @WorkerThread
    private fun grades(): Boolean {
        val grades = SagresNavigator.instance.getCurrentGrades()
        return when (grades.status) {
            Status.SUCCESS -> {
                defineSemesters(grades.semesters)
                defineGrades(grades.grades)
                defineFrequency(grades.frequency)

                Timber.d("Grades received: ${grades.grades}")
                Timber.d("Frequency: ${grades.frequency}")
                Timber.d("Semesters: ${grades.semesters}")

                gradesNotifications()
                frequencyNotifications()

                Timber.d("Completed!")
                true
            }
            else -> {
                produceErrorMessage(grades)
                false
            }
        }
    }

    private fun frequencyNotifications() {
        // TODO Implement frequency notifications
    }

    private fun gradesNotifications() {
        database.gradesDao().run {
            val posted = getPostedGradesDirect()
            val create = getCreatedGradesDirect()
            val change = getChangedGradesDirect()
            val date = getDateChangedGradesDirect()

            markAllNotified()

            posted.forEach { NotificationCreator.showSagresPostedGradesNotification(it, context) }
            create.forEach { NotificationCreator.showSagresCreateGradesNotification(it, context) }
            change.forEach { NotificationCreator.showSagresChangeGradesNotification(it, context) }
            date.forEach { NotificationCreator.showSagresDateGradesNotification(it, context) }
        }
    }

    @WorkerThread
    private fun messagesNotifications() {
        val messages = database.messageDao().getNewMessages()
        database.messageDao().setAllNotified()
        messages.forEach { it.notify(context) }
    }

    @WorkerThread
    private fun defineFrequency(frequency: List<SDisciplineMissedClass>?) {
        if (frequency == null) return
        database.classAbsenceDao().putAbsences(frequency)
    }

    @WorkerThread
    private fun defineGrades(grades: List<SGrade>) {
        database.gradesDao().putGrades(grades)
    }

    @WorkerThread
    private fun defineSemesters(semesters: List<Pair<Long, String>>) {
        semesters.forEach {
            val semester = Semester(sagresId = it.first, name = it.second, codename = it.second)
            database.semesterDao().insertIgnoring(semester)
        }
    }

    @WorkerThread
    private fun defineSchedule(locations: List<SDisciplineClassLocation>?) {
        if (locations == null) return
        database.classLocationDao().putSchedule(locations)
    }

    @WorkerThread
    private fun defineDisciplineGroups(groups: List<SDisciplineGroup>) {
        val values = ArrayList<ClassGroup>()
        groups.forEach {
            val group = database.classGroupDao().insert(it)
            values.add(group)
        }
    }

    @WorkerThread
    private fun defineDisciplines(disciplines: List<SDiscipline>) {
        val values = disciplines.map { Discipline.fromSagres(it) }
        database.disciplineDao().insert(values)
        disciplines.forEach { database.classDao().insert(it) }
    }

    @WorkerThread
    private fun defineCalendar(calendar: List<SCalendar>?) {
        val values = calendar?.map { CalendarItem.fromSagres(it) }
        database.calendarDao().deleteAndInsert(values)
    }

    private fun defineDemand(demandOpen: Boolean) {
        val flags = database.flagsDao().getFlagsDirect()
        if (flags == null) database.flagsDao().insertFlags(SagresFlags())

        database.flagsDao().updateDemand(demandOpen)
    }

    private fun produceErrorMessage(callback: BaseCallback<*>) {
        Timber.e("Failed executing with status ${callback.status} and throwable message [${callback.throwable?.message}]")
    }

    @MainThread
    fun asyncSync() {
        executors.networkIO().execute { performSync("Manual") }
    }

    companion object {
        private val S_LOCK = Any()
    }
}