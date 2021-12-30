/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.showdetails.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tivi.api.UiMessageManager
import app.tivi.domain.interactors.ChangeSeasonFollowStatus
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus.Action
import app.tivi.domain.interactors.ChangeSeasonWatchedStatus.Params
import app.tivi.domain.interactors.ChangeShowFollowStatus
import app.tivi.domain.interactors.ChangeShowFollowStatus.Action.TOGGLE
import app.tivi.domain.interactors.UpdateRelatedShows
import app.tivi.domain.interactors.UpdateShowDetails
import app.tivi.domain.interactors.UpdateShowImages
import app.tivi.domain.interactors.UpdateShowSeasons
import app.tivi.domain.observers.ObserveRelatedShows
import app.tivi.domain.observers.ObserveShowDetails
import app.tivi.domain.observers.ObserveShowFollowStatus
import app.tivi.domain.observers.ObserveShowImages
import app.tivi.domain.observers.ObserveShowNextEpisodeToWatch
import app.tivi.domain.observers.ObserveShowSeasonsEpisodesWatches
import app.tivi.domain.observers.ObserveShowViewStats
import app.tivi.extensions.combine
import app.tivi.util.Logger
import app.tivi.util.ObservableLoadingCounter
import app.tivi.util.collectStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class ShowDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val updateShowDetails: UpdateShowDetails,
    observeShowDetails: ObserveShowDetails,
    observeShowImages: ObserveShowImages,
    private val updateShowImages: UpdateShowImages,
    private val updateRelatedShows: UpdateRelatedShows,
    observeRelatedShows: ObserveRelatedShows,
    private val updateShowSeasons: UpdateShowSeasons,
    observeShowSeasons: ObserveShowSeasonsEpisodesWatches,
    private val changeSeasonWatchedStatus: ChangeSeasonWatchedStatus,
    observeShowFollowStatus: ObserveShowFollowStatus,
    observeNextEpisodeToWatch: ObserveShowNextEpisodeToWatch,
    observeShowViewStats: ObserveShowViewStats,
    private val changeShowFollowStatus: ChangeShowFollowStatus,
    private val changeSeasonFollowStatus: ChangeSeasonFollowStatus,
    private val logger: Logger,
) : ViewModel() {
    private val showId: Long = savedStateHandle.get("showId")!!

    private val loadingState = ObservableLoadingCounter()
    private val uiMessageManager = UiMessageManager()

    private val pendingActions = MutableSharedFlow<ShowDetailsAction>()

    val state = combine(
        observeShowFollowStatus.flow,
        observeShowDetails.flow,
        observeShowImages.flow,
        loadingState.observable,
        observeRelatedShows.flow,
        observeNextEpisodeToWatch.flow,
        observeShowSeasons.flow,
        observeShowViewStats.flow,
        uiMessageManager.messages,
    ) { isFollowed, show, showImages, refreshing, relatedShows, nextEpisode, seasons, stats,
        messages ->
        ShowDetailsViewState(
            isFollowed = isFollowed,
            show = show,
            posterImage = showImages.poster,
            backdropImage = showImages.backdrop,
            relatedShows = relatedShows,
            nextEpisodeToWatch = nextEpisode,
            seasons = seasons,
            watchStats = stats,
            refreshing = refreshing,
            messages = messages,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ShowDetailsViewState.Empty,
    )

    init {
        viewModelScope.launch {
            pendingActions.collect { action ->
                when (action) {
                    is ShowDetailsAction.RefreshAction -> refresh(true)
                    ShowDetailsAction.FollowShowToggleAction -> onToggleMyShowsButtonClicked()
                    is ShowDetailsAction.MarkSeasonWatchedAction -> onMarkSeasonWatched(action)
                    is ShowDetailsAction.MarkSeasonUnwatchedAction -> onMarkSeasonUnwatched(action)
                    is ShowDetailsAction.ChangeSeasonFollowedAction -> onChangeSeasonFollowState(action)
                    is ShowDetailsAction.UnfollowPreviousSeasonsFollowedAction -> onUnfollowPreviousSeasonsFollowState(action)
                    is ShowDetailsAction.ClearMessage -> uiMessageManager.clearMessage(action.id)
                    else -> Unit
                }
            }
        }

        observeShowFollowStatus(ObserveShowFollowStatus.Params(showId))
        observeShowDetails(ObserveShowDetails.Params(showId))
        observeShowImages(ObserveShowImages.Params(showId))
        observeRelatedShows(ObserveRelatedShows.Params(showId))
        observeShowSeasons(ObserveShowSeasonsEpisodesWatches.Params(showId))
        observeNextEpisodeToWatch(ObserveShowNextEpisodeToWatch.Params(showId))
        observeShowViewStats(ObserveShowViewStats.Params(showId))

        refresh(false)
    }

    private fun refresh(fromUser: Boolean) {
        viewModelScope.launch {
            updateShowDetails(
                UpdateShowDetails.Params(showId, fromUser)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
        viewModelScope.launch {
            updateShowImages(
                UpdateShowImages.Params(showId, fromUser)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
        viewModelScope.launch {
            updateRelatedShows(
                UpdateRelatedShows.Params(showId, fromUser)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
        viewModelScope.launch {
            updateShowSeasons(
                UpdateShowSeasons.Params(showId, fromUser)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
    }

    fun submitAction(action: ShowDetailsAction) {
        viewModelScope.launch { pendingActions.emit(action) }
    }

    private fun onToggleMyShowsButtonClicked() {
        viewModelScope.launch {
            changeShowFollowStatus(
                ChangeShowFollowStatus.Params(showId, TOGGLE)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
    }

    private fun onMarkSeasonWatched(action: ShowDetailsAction.MarkSeasonWatchedAction) {
        viewModelScope.launch {
            changeSeasonWatchedStatus(
                Params(action.seasonId, Action.WATCHED, action.onlyAired, action.date)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
    }

    private fun onMarkSeasonUnwatched(action: ShowDetailsAction.MarkSeasonUnwatchedAction) {
        viewModelScope.launch {
            changeSeasonWatchedStatus(
                Params(action.seasonId, Action.UNWATCH)
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
    }

    private fun onChangeSeasonFollowState(action: ShowDetailsAction.ChangeSeasonFollowedAction) {
        viewModelScope.launch {
            changeSeasonFollowStatus(
                ChangeSeasonFollowStatus.Params(
                    action.seasonId,
                    when {
                        action.followed -> ChangeSeasonFollowStatus.Action.FOLLOW
                        else -> ChangeSeasonFollowStatus.Action.IGNORE
                    }
                )
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
    }

    private fun onUnfollowPreviousSeasonsFollowState(
        action: ShowDetailsAction.UnfollowPreviousSeasonsFollowedAction
    ) {
        viewModelScope.launch {
            changeSeasonFollowStatus(
                ChangeSeasonFollowStatus.Params(
                    action.seasonId,
                    ChangeSeasonFollowStatus.Action.IGNORE_PREVIOUS
                )
            ).collectStatus(loadingState, logger, uiMessageManager)
        }
    }
}
