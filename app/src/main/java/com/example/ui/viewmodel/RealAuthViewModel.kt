package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackendAuthRepository
import com.example.data.repository.CivilIdRegisterOutcome
import com.example.data.repository.PinLoginOutcome
import com.example.network.BackendEmployee
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RealAuthScreenState {
    CHECKING_CACHED_SESSION,
    PIN_LOGIN,
    CIVIL_ID_REGISTER,
    SIGNED_IN
}

data class RealAuthUiState(
    val screen: RealAuthScreenState = RealAuthScreenState.CHECKING_CACHED_SESSION,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val notEligibleForRealAccount: Boolean = false,
    val cachedEmployeeName: String? = null,
    val signedInEmployee: BackendEmployee? = null,
    val pinLockedForSeconds: Int? = null
)

/** Drives the real Civil-ID-then-PIN auth flow against the Artify Central Backend. */
class RealAuthViewModel(private val repository: BackendAuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RealAuthUiState())
    val uiState: StateFlow<RealAuthUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = if (repository.hasCachedSession()) {
            RealAuthUiState(screen = RealAuthScreenState.PIN_LOGIN, cachedEmployeeName = repository.cachedEmployeeName())
        } else {
            RealAuthUiState(screen = RealAuthScreenState.CIVIL_ID_REGISTER)
        }
    }

    fun switchToRegisterInstead() {
        _uiState.value = _uiState.value.copy(
            screen = RealAuthScreenState.CIVIL_ID_REGISTER,
            errorMessage = null,
            notEligibleForRealAccount = false
        )
    }

    fun registerWithCivilId(civilId: String, pin: String) {
        if (civilId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter your Civil ID.")
            return
        }
        if (!pin.matches(Regex("^[0-9]{4}$"))) {
            _uiState.value = _uiState.value.copy(errorMessage = "Choose a 4-digit PIN.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, notEligibleForRealAccount = false)
            when (val outcome = repository.registerWithCivilId(civilId.trim(), pin)) {
                is CivilIdRegisterOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    screen = RealAuthScreenState.SIGNED_IN,
                    signedInEmployee = outcome.employee
                )
                CivilIdRegisterOutcome.NotEligible -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notEligibleForRealAccount = true,
                    errorMessage = "This Civil ID isn't on the workforce roster yet. Ask your administrator to add you, or continue in Demo Mode below."
                )
                is CivilIdRegisterOutcome.Error -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = outcome.message)
            }
        }
    }

    fun loginWithPin(pin: String) {
        if (!pin.matches(Regex("^[0-9]{4}$"))) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter your 4-digit PIN.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val outcome = repository.loginWithPin(pin)) {
                is PinLoginOutcome.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    screen = RealAuthScreenState.SIGNED_IN,
                    signedInEmployee = outcome.employee
                )
                PinLoginOutcome.NeedsRegistration -> _uiState.value = RealAuthUiState(
                    screen = RealAuthScreenState.CIVIL_ID_REGISTER,
                    errorMessage = "This device needs to verify your Civil ID again."
                )
                is PinLoginOutcome.Locked -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    pinLockedForSeconds = outcome.secondsRemaining,
                    errorMessage = "Too many incorrect attempts. Try again in ${outcome.secondsRemaining / 60 + 1} min."
                )
                is PinLoginOutcome.IncorrectPin -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (outcome.attemptsRemaining != null) {
                        "Incorrect PIN. ${outcome.attemptsRemaining} attempt(s) remaining."
                    } else "Incorrect PIN."
                )
                is PinLoginOutcome.Error -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = outcome.message)
            }
        }
    }

    fun enterDemoMode(employee: BackendEmployee) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = null,
            screen = RealAuthScreenState.SIGNED_IN,
            signedInEmployee = employee.copy(isDemo = true)
        )
    }

    fun logout() {
        viewModelScope.launch {
            if (_uiState.value.signedInEmployee?.isDemo != true) {
                repository.logout()
            }
            _uiState.value = RealAuthUiState(screen = RealAuthScreenState.CIVIL_ID_REGISTER)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
