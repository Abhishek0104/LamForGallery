package com.example.lamforgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.PersonDao
import com.example.lamforgallery.database.PersonUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PeopleUiState(
    val people: List<PersonUiModel> = emptyList(), // Changed to PersonUiModel
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

            // Fetch using the new query that counts distinct images
            val peopleList = personDao.getAllPeopleWithImageCount()

            // Sort by image count (most frequent people first)
            val sortedPeople = peopleList.sortedByDescending { it.imageCount }

            _uiState.update { it.copy(people = sortedPeople, isLoading = false) }
        }
    }
}