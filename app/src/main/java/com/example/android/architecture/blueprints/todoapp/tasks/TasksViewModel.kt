/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.architecture.blueprints.todoapp.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import com.example.android.architecture.blueprints.todoapp.ADD_EDIT_RESULT_OK
import com.example.android.architecture.blueprints.todoapp.DELETE_RESULT_OK
import com.example.android.architecture.blueprints.todoapp.EDIT_RESULT_OK
import com.example.android.architecture.blueprints.todoapp.R
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.data.TaskRepository
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ACTIVE_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ALL_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.COMPLETED_TASKS
import com.example.android.architecture.blueprints.todoapp.util.Async
import com.example.android.architecture.blueprints.todoapp.util.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState for the task list screen.
 */
interface TasksUiState {
    val items: List<Task>
    val isLoading: Boolean
    val filteringUiInfo: FilteringUiInfo
    val userMessage: Int?
}

/**
 * ViewModel for the task list screen.
 */
@OptIn(SavedStateHandleSaveableApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    var filterType: TasksFilterType by savedStateHandle.saveable { mutableStateOf(ALL_TASKS) }

    var userMessage: Int? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    val tasksAsyncFlow: StateFlow<Async<List<Task>>> = taskRepository.getTasksStream()
        .map { Async.Success(it) }
        .catch<Async<List<Task>>> { emit(Async.Error(R.string.loading_tasks_error)) }
        .stateIn(viewModelScope, WhileUiSubscribed, initialValue = Async.Loading)

    @Composable
    fun uiState(): TasksUiState {
        val tasksAsync by tasksAsyncFlow.collectAsStateWithLifecycle()
        return remember { UiState(tasksAsync = { tasksAsync }) }
    }

    /** This is the UI state logic, which we only want to execute when the UI is present. */
    private inner class UiState(
        val tasksAsync: () -> Async<List<Task>>
    ): TasksUiState {
        override val isLoading: Boolean
            get() = this@TasksViewModel.isLoading

        override val userMessage: Int?
            get() = when (val tasks = tasksAsync()) {
                is Async.Loading -> null
                is Async.Error -> tasks.errorMessage
                is Async.Success ->
                this@TasksViewModel.userMessage
            }

        override val items: List<Task>
            get() =
                when (val allItems = tasksAsync()) {
                    is Async.Success -> filterTasks(allItems.data, filterType)
                    else -> emptyList()
                }

        override val filteringUiInfo: FilteringUiInfo
            get() = getFilterUiInfo(filterType)
    }

    fun clearCompletedTasks() {
        viewModelScope.launch {
            taskRepository.clearCompletedTasks()
            userMessage = R.string.completed_tasks_cleared
            refresh()
        }
    }

    fun completeTask(task: Task, completed: Boolean) = viewModelScope.launch {
        if (completed) {
            taskRepository.completeTask(task.id)
            userMessage = R.string.task_marked_complete
        } else {
            taskRepository.activateTask(task.id)
            userMessage = R.string.task_marked_active
        }
    }

    fun showEditResultMessage(result: Int) {
        when (result) {
            EDIT_RESULT_OK -> {
                userMessage = R.string.successfully_saved_task_message
            }
            ADD_EDIT_RESULT_OK -> {
                userMessage = R.string.successfully_added_task_message
            }
            DELETE_RESULT_OK -> {
                userMessage = R.string.successfully_deleted_task_message
            }
        }
    }

    fun snackbarMessageShown() {
        userMessage = null
    }

    fun refresh() {
        isLoading = true
        viewModelScope.launch {
            taskRepository.refresh()
            isLoading = false
        }
    }

    private fun filterTasks(tasks: List<Task>, filteringType: TasksFilterType): List<Task> {
        val tasksToShow = ArrayList<Task>()
        // We filter the tasks based on the requestType
        for (task in tasks) {
            when (filteringType) {
                ALL_TASKS -> tasksToShow.add(task)
                ACTIVE_TASKS -> if (task.isActive) {
                    tasksToShow.add(task)
                }
                COMPLETED_TASKS -> if (task.isCompleted) {
                    tasksToShow.add(task)
                }
            }
        }
        return tasksToShow
    }

    // TODO: move this to the UI, or make it @Composable
    private fun getFilterUiInfo(requestType: TasksFilterType): FilteringUiInfo =
        when (requestType) {
            ALL_TASKS -> {
                FilteringUiInfo(
                    R.string.label_all, R.string.no_tasks_all,
                    R.drawable.logo_no_fill
                )
            }
            ACTIVE_TASKS -> {
                FilteringUiInfo(
                    R.string.label_active, R.string.no_tasks_active,
                    R.drawable.ic_check_circle_96dp
                )
            }
            COMPLETED_TASKS -> {
                FilteringUiInfo(
                    R.string.label_completed, R.string.no_tasks_completed,
                    R.drawable.ic_verified_user_96dp
                )
            }
        }
}

data class FilteringUiInfo(
    val currentFilteringLabel: Int = R.string.label_all,
    val noTasksLabel: Int = R.string.no_tasks_all,
    val noTaskIconRes: Int = R.drawable.logo_no_fill,
)
