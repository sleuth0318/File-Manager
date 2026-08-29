package com.goodwy.filemanager.fragments

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import androidx.core.view.ScrollingView
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SimpleActivity
import com.goodwy.filemanager.databinding.NetworkFragmentBinding
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.ftp.FtpServerState
import com.goodwy.filemanager.ftp.FtpServerUiState
import com.goodwy.filemanager.services.FtpServerService

class NetworkFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment<MyViewPagerFragment.NetworkInnerBinding>(context, attributeSet) {
    private lateinit var binding: NetworkFragmentBinding
    private var isUpdatingUi = false
    private var latestState = FtpServerState.current

    private val ftpStateListener: (FtpServerUiState) -> Unit = { state ->
        latestState = state
        renderState(state)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = NetworkFragmentBinding.bind(this)
        innerBinding = NetworkInnerBinding(binding)
    }

    override fun setupFragment(activity: SimpleActivity) {
        if (this.activity == null) {
            this.activity = activity
            setupActions()
            FtpServerState.registerListener(ftpStateListener)
        }
    }

    override fun onDetachedFromWindow() {
        FtpServerState.unregisterListener(ftpStateListener)
        super.onDetachedFromWindow()
    }

    override fun onResume(textColor: Int) {
        applyColors(textColor)
        binding.networkShowHiddenFiles.isChecked = context.config.showHidden
        renderState(latestState)
    }

    override fun refreshFragment() {
        renderState(FtpServerState.current)
    }

    override fun searchQueryChanged(text: String) {
        // Network tab does not participate in file search.
    }

    override fun myRecyclerView(): ScrollingView {
        return binding.networkScrollView
    }

    private fun setupActions() {
        binding.networkStartService.setOnClickListener {
            FtpServerService.start(context)
        }

        binding.networkStopService.setOnClickListener {
            FtpServerService.stop(context)
        }

        binding.networkShowHiddenFiles.setOnClickListener {
            context.config.showHidden = binding.networkShowHiddenFiles.isChecked
        }

        binding.networkUsername.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (isUpdatingUi) return
                val username = editable?.toString().orEmpty().ifBlank { "nothing" }
                context.config.ftpUsername = username
                FtpServerState.update { state -> state.copy(username = username) }
            }
        })
    }

    private fun renderState(state: FtpServerUiState) {
        if (!::binding.isInitialized) return

        val isRunningOrStarting = state.isRunning || state.isStarting
        binding.networkStoppedState.beGoneIf(isRunningOrStarting)
        binding.networkRunningState.beVisibleIf(isRunningOrStarting)
        binding.networkStartService.isEnabled = !state.isStarting
        binding.networkStopService.isEnabled = !state.isStarting || state.isRunning

        val addressText = state.address.ifBlank { context.getString(R.string.ftp_address_not_available) }
        binding.networkFtpAddress.text = addressText
        binding.networkPassword.text = state.password

        val username = state.username.ifBlank { context.config.ftpUsername }
        if (binding.networkUsername.text.toString() != username) {
            isUpdatingUi = true
            binding.networkUsername.setText(username)
            binding.networkUsername.setSelection(binding.networkUsername.text.length)
            isUpdatingUi = false
        }

        if (state.error.isBlank()) {
            binding.networkError.beGone()
        } else {
            binding.networkError.text = state.error
            binding.networkError.beVisible()
        }
    }

    private fun applyColors(textColor: Int) {
        val primaryColor = context.getProperPrimaryColor()
        binding.networkDevicesIcon.applyColorFilter(textColor)
        binding.networkStatusText.setTextColor(textColor)
        binding.networkShowHiddenFiles.setTextColor(textColor)
        binding.networkStartDescription.setTextColor(textColor)
        binding.networkStopDescription.setTextColor(textColor)
        binding.networkFtpAddress.setTextColor(primaryColor)
        binding.networkUsername.setTextColor(primaryColor)
        binding.networkUsername.setHintTextColor(primaryColor)
        binding.networkPassword.setTextColor(primaryColor)
    }
}
