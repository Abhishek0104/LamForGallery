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

data class PeopleUiState(
    val people: List<Person> = emptyList(),
    val isLoading: Boolean = false
)

class PeopleViewModel(
    private val personDao: PersonDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    fun loadPeople() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val peopleList = personDao.getAllPeople()
            // Sort by face count (most frequent people first)
            val sortedPeople = peopleList.sortedByDescending { it.faceCount }
            _uiState.update { it.copy(people = sortedPeople, isLoading = false) }
        }
    }

    fun updatePersonName(person: Person, newName: String) {
        viewModelScope.launch {
            personDao.updateName(person.id, newName)
            // Reload to reflect changes
            loadPeople()
        }
    }
}