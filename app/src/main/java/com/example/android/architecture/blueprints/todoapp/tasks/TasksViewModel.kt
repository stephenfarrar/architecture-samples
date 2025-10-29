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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
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
    val userMessage: Int?
}

/** Obtains the [TasksUiState]. */
// NOTE(step): An alternative factorization would be make the properties on [TasksUiState] into
// @Composable functions. Then you can obtain an instance of TasksUiState without needing to be
// @Composable. Perhaps I would prefer that.
@Composable
fun rememberTasksUiState(vm: TasksViewModel = hiltViewModel()): TasksUiState {
    val tasksAsyncState = vm.tasksAsyncFlow.collectAsStateWithLifecycle()
    return remember(vm, tasksAsyncState) { TasksUiStateImpl(vm) { tasksAsyncState.value } }
}

/** Interprets how to display state from the [TasksViewModel]. */
private class TasksUiStateImpl(
    val vm: TasksViewModel,
    val tasksAsync: () -> Async<List<Task>>
): TasksUiState {
    override val isLoading: Boolean
        // NOTE(step): I find this easier to reason about than lines 81 and 90 of
        // https://github.com/android/architecture-samples/blob/ee66e1526b84c026615df032c705842b7d2a521f/app/src/main/java/com/example/android/architecture/blueprints/todoapp/tasks/TasksViewModel.kt#L81
        get() = tasksAsync() is Async.Loading || vm.isLoading

    override val userMessage: Int?
        get() = when (val tasks = tasksAsync()) {
            // NOTE(step): This is the current logic, but I don't think it's correct to suppress
            // the userMessage. Not when the screen calls `currentOnUserMessageDisplayed()` as
            // though setting the userMessage is enough to display it.
            is Async.Loading -> null
            is Async.Error -> tasks.errorMessage
            is Async.Success -> vm.userMessage
        }

    override val items: List<Task>
        get() =
            when (val allItems = tasksAsync()) {
                is Async.Success -> filterTasks(allItems.data, vm.filterType)
                else -> emptyList()
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
