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

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

/** Returns whether the UI is currently in a loading state. */
@Composable
fun TasksViewModel.isLoading(): Boolean {
    val tasksAsync by tasksAsyncFlow.collectAsStateWithLifecycle()
    return isLoading || tasksAsync is Async.Loading
}

/** Returns the user message the UI should display. */
@Composable
fun TasksViewModel.userMessage(): Int? {
    val tasksAsync by tasksAsyncFlow.collectAsStateWithLifecycle()
    return when (val tasksAsync = tasksAsync) {
        // NOTE(step): This is the current logic, but I don't think it's correct to suppress
        // the userMessage. The screen calls `currentOnUserMessageDisplayed()` as though setting the
        // userMessage is enough to display it.
        is Async.Loading -> null
        is Async.Error -> tasksAsync.errorMessage
        is Async.Success -> userMessage
    }
}

/** Returns the list of items the UI should display. */
@Composable
fun TasksViewModel.items(): List<Task> {
    val tasksAsync = tasksAsyncFlow.collectAsStateWithLifecycle().value
    if (tasksAsync !is Async.Success) return emptyList()
    return when (filterType) {
        ALL_TASKS -> tasksAsync.data
        ACTIVE_TASKS -> tasksAsync.data.filter { it.isActive }
        COMPLETED_TASKS -> tasksAsync.data.filter { it.isCompleted }
    }
}

/** Returns the message ID corresponding to the edit result ID. */
@StringRes fun editResultMessage(result: Int): Int? =
    when (result) {
        EDIT_RESULT_OK -> R.string.successfully_saved_task_message
        ADD_EDIT_RESULT_OK -> R.string.successfully_added_task_message
        DELETE_RESULT_OK -> R.string.successfully_deleted_task_message
        else -> null
    }

@Composable
fun TasksFilterType.currentFilteringLabel() = stringResource(when(this) {
    ALL_TASKS -> R.string.label_all
    ACTIVE_TASKS -> R.string.label_active
    COMPLETED_TASKS -> R.string.label_completed
})

@Composable
fun TasksFilterType.noTasksLabel() = stringResource(when(this) {
    ALL_TASKS -> R.string.no_tasks_all
    ACTIVE_TASKS -> R.string.no_tasks_active
    COMPLETED_TASKS -> R.string.no_tasks_completed
})

@Composable
fun TasksFilterType.noTasksIconPainter() = painterResource(when(this) {
    ALL_TASKS -> R.drawable.logo_no_fill
    ACTIVE_TASKS -> R.drawable.ic_check_circle_96dp
    COMPLETED_TASKS -> R.drawable.ic_verified_user_96dp
})

/** Holder of fragment-retained state for the Tasks UI. */
@OptIn(SavedStateHandleSaveableApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    var filterType: TasksFilterType by savedStateHandle.saveable { mutableStateOf(ALL_TASKS) }

    var userMessage: Int? by mutableStateOf(null)

    var isLoading: Boolean by mutableStateOf(false)
        private set

    val tasksAsyncFlow: StateFlow<Async<List<Task>>> = taskRepository.getTasksStream()
        .map { Async.Success(it) }
        .catch<Async<List<Task>>> { emit(Async.Error(R.string.loading_tasks_error)) }
        .stateIn(viewModelScope, WhileUiSubscribed, initialValue = Async.Loading)

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

    fun refresh() {
        isLoading = true
        viewModelScope.launch {
            taskRepository.refresh()
            isLoading = false
        }
    }
}
