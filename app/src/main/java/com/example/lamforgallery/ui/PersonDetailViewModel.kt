package com.example.lamforgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.Person
import com.example.lamforgallery.database.PersonDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- ViewModel ---

data class PersonDetailUiState(
    val person: Person? = null,
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false
)

class PersonDetailViewModel(
    private val personDao: PersonDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    fun loadPerson(personId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val person = personDao.getPersonById(personId)
            val photos = if (person != null) {
                personDao.getUrisForPeople(listOf(personId))
            } else emptyList()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    person = person,
                    photos = photos
                )
            }
        }
    }

    // --- NEW: Update Relation as well ---
    fun updatePersonDetails(newName: String, newRelation: String?) {
        val currentPerson = _uiState.value.person ?: return
        viewModelScope.launch {
            val relationToSave = if (newRelation.isNullOrBlank()) null else newRelation.trim()
            personDao.updatePersonDetails(currentPerson.id, newName, relationToSave)

            // Reload to reflect changes
            val updatedPerson = personDao.getPersonById(currentPerson.id)
            _uiState.update { it.copy(person = updatedPerson) }
        }
    }
}