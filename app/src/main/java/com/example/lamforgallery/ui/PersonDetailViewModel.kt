package com.example.lamforgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.Person
import com.example.lamforgallery.database.PersonDao
import com.example.lamforgallery.database.PersonUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- ViewModel ---

data class PersonDetailUiState(
    val person: Person? = null,
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val allPeople: List<PersonUiModel> = emptyList() // For the move dialog
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
            
            // Also load all people for the 'move' dialog
            val allPeople = personDao.getAllPeopleWithImageCount()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    person = person,
                    photos = photos,
                    allPeople = allPeople
                )
            }
        }
    }

    fun updatePersonDetails(newName: String, newRelation: String?) {
        val currentPerson = _uiState.value.person ?: return
        viewModelScope.launch {
            val relationToSave = if (newRelation.isNullOrBlank()) null else newRelation.trim()
            personDao.updatePersonDetails(currentPerson.id, newName, relationToSave)

            val updatedPerson = personDao.getPersonById(currentPerson.id)
            _uiState.update { it.copy(person = updatedPerson) }
        }
    }

    // --- NEW: Function to remove a photo from the current person ---
    fun removePhotoFromPerson(uri: String) {
        val personId = _uiState.value.person?.id ?: return
        viewModelScope.launch {
            personDao.removePhotoFromPerson(uri, personId)
            // Refresh the photo list
            _uiState.update {
                it.copy(photos = it.photos.filterNot { photoUri -> photoUri == uri })
            }
        }
    }

    // --- NEW: Function to move a photo to a different person ---
    fun movePhotoToPerson(uri: String, newPersonId: String) {
        val oldPersonId = _uiState.value.person?.id ?: return
        if (oldPersonId == newPersonId) return // No change needed

        viewModelScope.launch {
            personDao.movePhotoToPerson(uri, oldPersonId, newPersonId)
            // Refresh the photo list
            _uiState.update {
                it.copy(photos = it.photos.filterNot { photoUri -> photoUri == uri })
            }
        }
    }
}