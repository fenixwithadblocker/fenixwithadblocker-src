/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.account

import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.ConstellationState
import mozilla.components.concept.sync.DeviceConstellationObserver
import mozilla.components.lib.state.ext.consumeFrom
import mozilla.components.service.fxa.SyncEngine
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.manager.SyncEnginesStorage
import mozilla.components.service.fxa.sync.SyncReason
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.service.fxa.sync.getLastSynced
import mozilla.components.support.ktx.android.content.getColorFromAttr
import org.mozilla.fenix.R
import org.mozilla.fenix.components.FenixSnackbar
import org.mozilla.fenix.components.StoreProvider
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.secure
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.settings.requirePreference

@SuppressWarnings("TooManyFunctions", "LargeClass")
class AccountSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var accountManager: FxaAccountManager
    private lateinit var accountSettingsStore: AccountSettingsFragmentStore
    private lateinit var accountSettingsInteractor: AccountSettingsInteractor

    // Navigate away from this fragment when we encounter auth problems or logout events.
    private val accountStateObserver = object : AccountObserver {
        override fun onAuthenticationProblems() {
            viewLifecycleOwner.lifecycleScope.launch {
                findNavController().popBackStack()
            }
        }

        override fun onLoggedOut() {
            viewLifecycleOwner.lifecycleScope.launch {
                findNavController().popBackStack()

                // Remove the device name when we log out.
                context?.let {
                    val deviceNameKey = it.getPreferenceKey(R.string.pref_key_sync_device_name)
                    preferenceManager.sharedPreferences.edit().remove(deviceNameKey).apply()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.preferences_account_settings))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireComponents.analytics.metrics.track(Event.SyncAccountOpened)
    }

    override fun onDestroy() {
        super.onDestroy()
        requireComponents.analytics.metrics.track(Event.SyncAccountClosed)
    }

    @ExperimentalCoroutinesApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        consumeFrom(accountSettingsStore) {
            updateLastSyncTimePref(it)
            updateDeviceName(it)
        }

        accountSettingsInteractor = AccountSettingsInteractor(
            findNavController(),
            ::syncNow,
            ::syncDeviceName,
            accountSettingsStore
        )
    }

    @Suppress("ComplexMethod", "LongMethod")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.account_settings_preferences, rootKey)

        accountSettingsStore = StoreProvider.get(this) {
            AccountSettingsFragmentStore(
                AccountSettingsFragmentState(
                    lastSyncedDate =
                    if (getLastSynced(requireContext()) == 0L)
                        LastSyncTime.Never
                    else
                        LastSyncTime.Success(getLastSynced(requireContext())),
                    deviceName = requireComponents.backgroundServices.defaultDeviceName(
                        requireContext()
                    )
                )
            )
        }

        accountManager = requireComponents.backgroundServices.accountManager
        accountManager.register(accountStateObserver, this, true)

        // Sign out
        val preferenceSignOut = requirePreference<Preference>(R.string.pref_key_sign_out)
        preferenceSignOut.onPreferenceClickListener = getClickListenerForSignOut()

        // Sync now
        val preferenceSyncNow = requirePreference<Preference>(R.string.pref_key_sync_now)
        preferenceSyncNow.apply {
            onPreferenceClickListener = getClickListenerForSyncNow()

            icon = icon.mutate().apply {
                setTint(context.getColorFromAttr(R.attr.primaryText))
            }

            // Current sync state
            if (requireComponents.backgroundServices.accountManager.isSyncActive()) {
                title = getString(R.string.sync_syncing_in_progress)
                isEnabled = false
            } else {
                isEnabled = true
            }
        }

        // Device Name
        val deviceConstellation = accountManager.authenticatedAccount()?.deviceConstellation()
        requirePreference<EditTextPreference>(R.string.pref_key_sync_device_name).apply {
            onPreferenceChangeListener = getChangeListenerForDeviceName()
            deviceConstellation?.state()?.currentDevice?.let { device ->
                summary = device.displayName
                text = device.displayName
                accountSettingsStore.dispatch(AccountSettingsFragmentAction.UpdateDeviceName(device.displayName))
            }
            setOnBindEditTextListener { editText ->
                editText.filters = arrayOf(InputFilter.LengthFilter(DEVICE_NAME_MAX_LENGTH))
                editText.minHeight = resources.getDimensionPixelSize(R.dimen.account_settings_device_name_min_height)
            }
        }

        // Make sure out sync engine checkboxes are up-to-date and disabled if currently syncing
        updateSyncEngineStates()
        setDisabledWhileSyncing(accountManager.isSyncActive())

        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_history).apply {
            setOnPreferenceChangeListener { _, newValue ->
                SyncEnginesStorage(context).setStatus(SyncEngine.History, newValue as Boolean)
                @Suppress("DeferredResultUnused")
                context.components.backgroundServices.accountManager.syncNowAsync(SyncReason.EngineChange)
                true
            }
        }

        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_bookmarks).apply {
            setOnPreferenceChangeListener { _, newValue ->
                SyncEnginesStorage(context).setStatus(SyncEngine.Bookmarks, newValue as Boolean)
                @Suppress("DeferredResultUnused")
                context.components.backgroundServices.accountManager.syncNowAsync(SyncReason.EngineChange)
                true
            }
        }

        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_logins).apply {
            setOnPreferenceChangeListener { _, newValue ->
                val manager =
                    activity?.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (manager.isKeyguardSecure ||
                    newValue == false ||
                    !context.settings().shouldShowSecurityPinWarningSync
                ) {
                    SyncEnginesStorage(context).setStatus(SyncEngine.Passwords, newValue as Boolean)
                    @Suppress("DeferredResultUnused")
                    context.components.backgroundServices.accountManager.syncNowAsync(SyncReason.EngineChange)
                } else {
                    showPinDialogWarning(newValue as Boolean)
                }
                true
            }
        }

        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_tabs).apply {
            setOnPreferenceChangeListener { _, newValue ->
                SyncEnginesStorage(context).setStatus(SyncEngine.Tabs, newValue as Boolean)
                @Suppress("DeferredResultUnused")
                context.components.backgroundServices.accountManager.syncNowAsync(SyncReason.EngineChange)
                true
            }
        }

        deviceConstellation?.registerDeviceObserver(
            deviceConstellationObserver,
            owner = this,
            autoPause = true
        )

        // NB: ObserverRegistry will take care of cleaning up internal references to 'observer' and
        // 'owner' when appropriate.
        requireComponents.backgroundServices.accountManager.registerForSyncEvents(
            syncStatusObserver, owner = this, autoPause = true
        )
    }

    private fun showPinDialogWarning(newValue: Boolean) {
        context?.let {
            AlertDialog.Builder(it).apply {
                setTitle(getString(R.string.logins_warning_dialog_title))
                setMessage(
                    getString(R.string.logins_warning_dialog_message)
                )

                setNegativeButton(getString(R.string.logins_warning_dialog_later)) { _: DialogInterface, _ ->
                    SyncEnginesStorage(context).setStatus(SyncEngine.Passwords, newValue)
                    @Suppress("DeferredResultUnused")
                    context.components.backgroundServices.accountManager.syncNowAsync(SyncReason.EngineChange)
                }

                setPositiveButton(getString(R.string.logins_warning_dialog_set_up_now)) { it: DialogInterface, _ ->
                    it.dismiss()
                    val intent = Intent(
                        Settings.ACTION_SECURITY_SETTINGS
                    )
                    startActivity(intent)
                }
                create()
            }.show().secure(activity)
            it.settings().incrementShowLoginsSecureWarningSyncCount()
        }
    }

    private fun updateSyncEngineStates() {
        val syncEnginesStatus = SyncEnginesStorage(requireContext()).getStatus()
        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_bookmarks).apply {
            isEnabled = syncEnginesStatus.containsKey(SyncEngine.Bookmarks)
            isChecked = syncEnginesStatus.getOrElse(SyncEngine.Bookmarks) { true }
        }
        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_history).apply {
            isEnabled = syncEnginesStatus.containsKey(SyncEngine.History)
            isChecked = syncEnginesStatus.getOrElse(SyncEngine.History) { true }
        }
        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_logins).apply {
            isEnabled = syncEnginesStatus.containsKey(SyncEngine.Passwords)
            isChecked = syncEnginesStatus.getOrElse(SyncEngine.Passwords) { true }
        }
        requirePreference<CheckBoxPreference>(R.string.pref_key_sync_tabs).apply {
            isEnabled = syncEnginesStatus.containsKey(SyncEngine.Tabs)
            isChecked = syncEnginesStatus.getOrElse(SyncEngine.Tabs) { true }
        }
    }

    private fun syncNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            requireComponents.analytics.metrics.track(Event.SyncAccountSyncNow)
            // Trigger a sync.
            requireComponents.backgroundServices.accountManager.syncNowAsync(SyncReason.User)
                .await()
            // Poll for device events & update devices.
            accountManager.authenticatedAccount()
                ?.deviceConstellation()?.run {
                    refreshDevicesAsync().await()
                    pollForCommandsAsync().await()
                }
        }
    }

    private fun syncDeviceName(newValue: String): Boolean {
        if (newValue.trim().isEmpty()) {
            return false
        }
        // This may fail, and we'll have a disparity in the UI until `updateDeviceName` is called.
        viewLifecycleOwner.lifecycleScope.launch(Main) {
            context?.let {
                accountManager.authenticatedAccount()
                    ?.deviceConstellation()
                    ?.setDeviceNameAsync(newValue, it)
                    ?.await()
            }
        }
        return true
    }

    private fun getClickListenerForSignOut(): Preference.OnPreferenceClickListener {
        return Preference.OnPreferenceClickListener {
            accountSettingsInteractor.onSignOut()
            true
        }
    }

    private fun getClickListenerForSyncNow(): Preference.OnPreferenceClickListener {
        return Preference.OnPreferenceClickListener {
            accountSettingsInteractor.onSyncNow()
            true
        }
    }

    private fun getChangeListenerForDeviceName(): Preference.OnPreferenceChangeListener {
        return Preference.OnPreferenceChangeListener { _, newValue ->
            accountSettingsInteractor.onChangeDeviceName(newValue as String) {
                FenixSnackbar.make(
                    view = requireView(),
                    duration = FenixSnackbar.LENGTH_LONG,
                    isDisplayedWithBrowserToolbar = false
                )
                    .setText(getString(R.string.empty_device_name_error))
                    .show()
            }
        }
    }

    private fun setDisabledWhileSyncing(isSyncing: Boolean) {
        requirePreference<PreferenceCategory>(R.string.preferences_sync_category).isEnabled = !isSyncing
        requirePreference<EditTextPreference>(R.string.pref_key_sync_device_name).isEnabled = !isSyncing
    }

    private val syncStatusObserver = object : SyncStatusObserver {
        private val pref by lazy { requirePreference<Preference>(R.string.pref_key_sync_now) }

        override fun onStarted() {
            viewLifecycleOwner.lifecycleScope.launch {
                view?.announceForAccessibility(getString(R.string.sync_syncing_in_progress))
                pref.title = getString(R.string.sync_syncing_in_progress)
                pref.isEnabled = false
                setDisabledWhileSyncing(true)
            }
        }

        // Sync stopped successfully.
        override fun onIdle() {
            viewLifecycleOwner.lifecycleScope.launch {
                pref.title = getString(R.string.preferences_sync_now)
                pref.isEnabled = true

                val time = getLastSynced(requireContext())
                accountSettingsStore.dispatch(AccountSettingsFragmentAction.SyncEnded(time))
                // Make sure out sync engine checkboxes are up-to-date.
                updateSyncEngineStates()
                setDisabledWhileSyncing(false)
            }
        }

        // Sync stopped after encountering a problem.
        override fun onError(error: Exception?) {
            viewLifecycleOwner.lifecycleScope.launch {
                pref.title = getString(R.string.preferences_sync_now)
                // We want to only enable the sync button, and not the checkboxes here
                pref.isEnabled = true

                val failedTime = getLastSynced(requireContext())
                accountSettingsStore.dispatch(
                    AccountSettingsFragmentAction.SyncFailed(
                        failedTime
                    )
                )
            }
        }
    }

    private val deviceConstellationObserver = object : DeviceConstellationObserver {
        override fun onDevicesUpdate(constellation: ConstellationState) {
            constellation.currentDevice?.displayName?.also {
                accountSettingsStore.dispatch(AccountSettingsFragmentAction.UpdateDeviceName(it))
            }
        }
    }

    private fun updateDeviceName(state: AccountSettingsFragmentState) {
        val preferenceDeviceName = requirePreference<Preference>(R.string.pref_key_sync_device_name)
        preferenceDeviceName.summary = state.deviceName
    }

    private fun updateLastSyncTimePref(state: AccountSettingsFragmentState) {
        val value = when (state.lastSyncedDate) {
            LastSyncTime.Never -> getString(R.string.sync_never_synced_summary)
            is LastSyncTime.Failed -> {
                if (state.lastSyncedDate.lastSync == 0L) {
                    getString(R.string.sync_failed_never_synced_summary)
                } else {
                    getString(
                        R.string.sync_failed_summary,
                        DateUtils.getRelativeTimeSpanString(state.lastSyncedDate.lastSync)
                    )
                }
            }
            is LastSyncTime.Success -> getString(
                R.string.sync_last_synced_summary,
                DateUtils.getRelativeTimeSpanString(state.lastSyncedDate.lastSync)
            )
        }

        requirePreference<Preference>(R.string.pref_key_sync_now).summary = value
    }

    companion object {
        private const val DEVICE_NAME_MAX_LENGTH = 128
        private const val DEVICE_NAME_EDIT_TEXT_MIN_HEIGHT_DP = 48
    }
}
