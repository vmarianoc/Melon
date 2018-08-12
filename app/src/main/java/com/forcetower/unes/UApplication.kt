package com.forcetower.unes

import android.app.Activity
import android.app.Application
import androidx.fragment.app.Fragment
import com.forcetower.sagres.SagresNavigator
import com.forcetower.unes.core.injection.AppInjection
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.support.HasSupportFragmentInjector
import timber.log.Timber
import javax.inject.Inject

class UApplication : Application(), HasActivityInjector, HasSupportFragmentInjector {
    @Inject
    lateinit var activityInjector: DispatchingAndroidInjector<Activity>
    @Inject
    lateinit var fragmentInjector: DispatchingAndroidInjector<Fragment>
    @Volatile
    private var injected = false

    override fun onCreate() {
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        injectApplicationIfNecessary()
        super.onCreate()
        configureSagresNavigator()
    }

    private fun createApplicationInjector(): AndroidInjector<UApplication> = AppInjection.create(this)

    private fun injectApplicationIfNecessary() {
        if (!injected) {
            synchronized(this) {
                if (!injected) {
                    createApplicationInjector().inject(this)
                    if (!injected)
                        throw IllegalStateException("Attempt to inject the app has failed")
                }
            }
        }
    }

    @Inject
    fun setInjected() {
        injected = true
    }

    fun configureSagresNavigator() {
        SagresNavigator.initialize(this)
    }

    override fun activityInjector(): AndroidInjector<Activity> = activityInjector
    override fun supportFragmentInjector(): AndroidInjector<Fragment> = fragmentInjector
}