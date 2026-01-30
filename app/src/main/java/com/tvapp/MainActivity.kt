package com.tvapp

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.tvapp.EpgRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var parentalControl: ParentalControl
    private lateinit var credentialsContainer: View
    private lateinit var mainContainer: ConstraintLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var saveButton: Button
    private lateinit var channelList: RecyclerView
    private lateinit var nowPlaying: TextView
    private lateinit var playerView: PlayerView
    private lateinit var backButton: Button
    private lateinit var settingsButton: Button
    private lateinit var listTitle: TextView
    private lateinit var searchInput: EditText
    private lateinit var loadingContainer: View

    private lateinit var leftPanel: View
    private lateinit var playerContainer: View
    private lateinit var epgContainer: View
    private lateinit var epgTitle: TextView
    private lateinit var epgTime: TextView
    private lateinit var epgDescription: TextView
    private lateinit var normalConstraints: ConstraintSet
    private lateinit var fullConstraints: ConstraintSet

    private var player: ExoPlayer? = null
    private var epgJob: Job? = null
    private var epgLoadJob: Job? = null
    private var epgRepo: EpgRepository? = null
    private var hasEpgData = false
    private var isEpisodeEpgActive = false
    private var isFullscreen = false
    private var defaultPlayerHeight: Int = 0
    private var currentStream: Stream? = null
    private var state: UiState = UiState.ShowGroups
    private var groups: List<Group> = emptyList()
    private var allStreams: List<Stream> = emptyList()
    private var seriesList: List<SeriesInfo> = emptyList()
    private lateinit var fullscreenButton: ImageButton
    private var baseUrl = ""
    private var username = ""
    private var password = ""
    private var playbackRetryJob: Job? = null
    private var consecutiveErrorCount = 0
    private var lastPlaybackAction: (() -> Unit)? = null

    private val playbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w("MainActivity", "Playback error: ${error.errorCodeName} - ${error.message}")
            consecutiveErrorCount++
            schedulePlaybackRetry()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && player?.playWhenReady == true) {
                resetRetryState()
            }
        }
    }

    private val adapter = ChannelAdapter(
        onChannelClick = { stream -> startPlayback(stream) },
        onGroupClick = { group, position -> showChannelsIn(group, position) },
        onSeriesClick = { series -> showSeriesSeasons(series) },
        onSeasonClick = { seriesId, season -> showSeasonEpisodes(seriesId, season) },
        onEpisodeClick = { seriesId, season, episode -> playEpisode(seriesId, season, episode) },
        onEpisodeFocus = { episode -> showEpisodeResume(episode) },
        onChannelLongClick = { stream -> toggleFavoriteStream(stream) },
        onSeriesLongClick = { series -> toggleFavoriteSeries(series) },
        onEpisodeLongClick = { seriesId, season, episode -> toggleFavoriteEpisode(seriesId, season, episode) },
        isStreamFavorite = { stream -> isFavoriteStream(stream) },
        isSeriesFavorite = { series -> isFavoriteSeries(series) },
        isEpisodeFavorite = { seriesId, season, episode -> isFavoriteEpisode(seriesId, season, episode) }
    )

    private var lastGroupPosition: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this) {
            when (state) {
                is UiState.ShowChannels -> showGroups()
                is UiState.ShowSearchResults -> showGroups()
                is UiState.ShowSeriesSeasons -> showGroups()
                is UiState.ShowSeasonEpisodes -> {
                    val seriesState = state as UiState.ShowSeriesSeasons
                    showSeriesSeasons(seriesList.find { it.series_id == seriesState.seriesId } ?: return@addCallback)
                }
                else -> finish()
            }
        }

        prefs = getSharedPreferences("xtream_prefs", MODE_PRIVATE)
        parentalControl = ParentalControl(prefs)
        credentialsContainer = findViewById(R.id.credentials_container)
        mainContainer = findViewById(R.id.main_container)
        urlInput = findViewById(R.id.url_input)
        usernameInput = findViewById(R.id.username_input)
        passwordInput = findViewById(R.id.password_input)
        saveButton = findViewById(R.id.save_button)
        channelList = findViewById(R.id.channel_list)
        nowPlaying = findViewById(R.id.now_playing)
        playerView = findViewById(R.id.player_view)
        leftPanel = findViewById(R.id.left_panel)
        playerContainer = findViewById(R.id.player_container)
        epgContainer = findViewById(R.id.epg_container)
        epgTitle = findViewById(R.id.epg_title)
        epgTime = findViewById(R.id.epg_time)
        epgDescription = findViewById(R.id.epg_description)
        backButton = findViewById(R.id.back_button)
        settingsButton = findViewById(R.id.settings_button)
        listTitle = findViewById(R.id.list_title)
        searchInput = findViewById(R.id.search_input)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        loadingContainer = findViewById(R.id.loading_container)
        defaultPlayerHeight = playerView.layoutParams.height

        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = adapter

        player = ExoPlayer.Builder(this).build().also {
            it.addListener(playbackListener)
            playerView.player = it
        }
        backButton.setOnClickListener { showGroups() }
        settingsButton.setOnClickListener { showParentalControlSettings() }
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        playerView.setOnClickListener { toggleFullscreen() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        normalConstraints = ConstraintSet().apply { clone(mainContainer) }
        fullConstraints = ConstraintSet().apply {
            clone(mainContainer)
            setVisibility(R.id.left_panel, View.GONE)
            setVisibility(R.id.now_playing, View.GONE)
            setVisibility(R.id.epg_container, View.GONE)
            clear(R.id.player_container)
            constrainWidth(R.id.player_container, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.player_container, ConstraintSet.MATCH_CONSTRAINT)
            connect(R.id.player_container, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(R.id.player_container, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(R.id.player_container, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(R.id.player_container, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            setMargin(R.id.player_container, ConstraintSet.TOP, 0)
            setMargin(R.id.player_container, ConstraintSet.BOTTOM, 0)
            setMargin(R.id.player_container, ConstraintSet.START, 0)
            setMargin(R.id.player_container, ConstraintSet.END, 0)
        }

        saveButton.setOnClickListener { saveCredentials() }

        val savedUrl = prefs.getString("url", null)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)

        if (savedUrl.isNullOrBlank() || savedUser.isNullOrBlank() || savedPass.isNullOrBlank()) {
            showCredentials()
        } else {
            showMain()
            loadPlaylist(savedUrl, savedUser, savedPass)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        epgJob?.cancel()
        epgLoadJob?.cancel()
        playbackRetryJob?.cancel()
        setKeepScreenOn(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (isFullscreen) {
                toggleFullscreen()
                return true
            } else if (state is UiState.ShowChannels) {
                showGroups()
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isFullscreen && player != null) {
            playerView.useController = true
            return true
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && isFullscreen && player != null) {
            playerView.useController = false
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showCredentials() {
        credentialsContainer.visibility = View.VISIBLE
        mainContainer.visibility = View.GONE
        
        // Set default credentials if fields are empty
        if (urlInput.text.isNullOrBlank()) {
            urlInput.setText("http://cdc55.cc")
        }
        if (usernameInput.text.isNullOrBlank()) {
            usernameInput.setText("4521486582")
        }
        if (passwordInput.text.isNullOrBlank()) {
            passwordInput.setText("3555")
        }
    }

    private fun showMain() {
        credentialsContainer.visibility = View.GONE
        mainContainer.visibility = View.VISIBLE
    }

    private fun saveCredentials() {
        val url = urlInput.text?.toString()?.trim().orEmpty()
        val username = usernameInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString()?.trim().orEmpty()

        var hasError = false
        if (url.isBlank()) {
            urlInput.error = "Required"
            hasError = true
        }
        if (username.isBlank()) {
            usernameInput.error = "Required"
            hasError = true
        }
        if (password.isBlank()) {
            passwordInput.error = "Required"
            hasError = true
        }
        if (hasError) {
            return
        }

        prefs.edit()
            .putString("url", url)
            .putString("username", username)
            .putString("password", password)
            .apply()

        showMain()
        loadPlaylist(url, username, password)
    }

    private fun loadPlaylist(url: String, username: String, password: String) {
        this.baseUrl = url
        this.username = username
        this.password = password
        loadEpgXml(url, username, password)
        showLoading()
        lifecycleScope.launch {
            val api = XtreamApi(url, username, password)
            val categories = withContext(Dispatchers.IO) { api.fetchCategories() }
            val streams = withContext(Dispatchers.IO) { api.fetchStreams() }
            val series = withContext(Dispatchers.IO) { api.fetchSeries() }

            allStreams = streams
            seriesList = series
            groups = buildGroups(categories, streams, series)
            hideLoading()
            showGroups()

            currentStream?.let { active ->
                // Refresh EPG using the latest credentials and data
                val matched = allStreams.find { it.stream_id == active.stream_id }
                if (matched != null) {
                    loadEpg(matched)
                } else {
                    hasEpgData = false
                    updateEpgVisibility()
                }
            }
        }
    }

    private fun buildGroups(categories: List<Category>, streams: List<Stream>, series: List<SeriesInfo>): List<Group> {
        val categoryMap = categories.associateBy { it.category_id }
        val grouped = streams.groupBy { it.category_id }
        val seriesGrouped = series.groupBy { it.category_id }
        val sortedCategories = categories.sortedBy { it.category_name.lowercase() }.toMutableList()
        if ((grouped.keys + seriesGrouped.keys).any { it !in categoryMap }) {
            sortedCategories.add(Category("0", "Other"))
        }

        val groups = mutableListOf<Group>()
        for (category in sortedCategories) {
            val inCategory = grouped[category.category_id].orEmpty()
            if (inCategory.isEmpty()) {
                continue
            }
            val channels = inCategory.sortedBy { it.name.lowercase() }
            groups.add(Group(category.category_name, channels))
        }
        return groups
    }

    private fun startPlayback(stream: Stream, fromRetry: Boolean = false) {
        if (fromRetry) {
            playbackRetryJob?.cancel()
        } else {
            resetRetryState()
        }
        val url = prefs.getString("url", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            showCredentials()
            return
        }
        lastPlaybackAction = { startPlayback(stream, fromRetry = true) }
        isEpisodeEpgActive = false
        currentStream = stream
        saveRecent(stream)
        val streamUrl = buildStreamUrl(url, username, password, stream)
        nowPlaying.text = "Now Playing: ${stream.name}"
        val mediaItem = MediaItem.fromUri(streamUrl)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            play()
        }
        setKeepScreenOn(true)
        playerView.useController = false
        loadEpg(stream)
    }

    private fun buildStreamUrl(
        baseUrl: String,
        username: String,
        password: String,
        stream: Stream
    ): String {
        val normalized = baseUrl.trimEnd('/')
        val extension = stream.container_extension?.ifBlank { "m3u8" } ?: "m3u8"
        // Determine if it's a VOD, Series or live stream based on stream_type
        val streamPath = when (stream.stream_type) {
            "movie" -> "movie"
            "series" -> "series"
            else -> "live"
        }
        val streamUrl = "${normalized}/${streamPath}/${username}/${password}/${stream.stream_id}.${extension}"
        Log.d("MainActivity", "Playing stream: $streamUrl")
        return streamUrl
    }

    private fun buildEpgUrl(baseUrl: String, username: String, password: String): String {
        val normalized = baseUrl.trimEnd('/')
        return "${normalized}/epg.php?username=${username}&password=${password}"
    }

    private fun loadEpgXml(baseUrl: String, username: String, password: String) {
        if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) return
        val epgUrl = buildEpgUrl(baseUrl, username, password)
        epgLoadJob?.cancel()
        epgRepo = null
        epgLoadJob = lifecycleScope.launch {
            try {
                val repo = withContext(Dispatchers.IO) {
                    EpgRepository().apply { load(epgUrl) }
                }
                epgRepo = repo
            } finally {
                epgLoadJob = null
                currentStream?.let { loadEpg(it) }
            }
        }
    }

    private fun loadEpg(stream: Stream) {
        if (isEpisodeEpgActive && state is UiState.ShowSeasonEpisodes) {
            updateEpgVisibility()
            return
        }

        isEpisodeEpgActive = false
        epgJob?.cancel()
        hasEpgData = false

        if (stream.stream_type != null && stream.stream_type != "live") {
            epgTitle.text = getString(R.string.epg_not_available)
            epgTime.text = ""
            epgDescription.text = getString(R.string.epg_description_not_available)
            updateEpgVisibility()
            return
        }

        if (epgRepo == null) {
            if (epgLoadJob == null) {
                loadEpgXml(baseUrl, username, password)
            }
            epgTitle.text = getString(R.string.epg_loading)
            epgTime.text = ""
            epgDescription.text = ""
            updateEpgVisibility()
            return
        }

        epgTitle.text = getString(R.string.epg_loading)
        epgTime.text = ""
        epgDescription.text = ""
        updateEpgVisibility()

        epgJob = lifecycleScope.launch {
            try {
                val current = withContext(Dispatchers.Default) { epgRepo?.currentProgram(stream.name) }
                if (current != null) {
                    hasEpgData = true
                    epgTitle.text = current.title
                    epgTime.text = formatEpgTime(current.startTimestamp, current.endTimestamp)
                    epgDescription.text = current.description?.ifBlank { getString(R.string.epg_description_not_available) }
                        ?: getString(R.string.epg_description_not_available)
                } else {
                    hasEpgData = false
                    epgTitle.text = getString(R.string.epg_not_available)
                    epgTime.text = ""
                    epgDescription.text = getString(R.string.epg_description_not_available)
                }
            } finally {
                epgJob = null
                updateEpgVisibility()
            }
        }
    }

    private fun showEpisodeResume(episode: Episode) {
        isEpisodeEpgActive = true
        epgJob?.cancel()

        val displayTitle = episode.title.ifBlank { "Episode ${episode.episode_num}" }
        epgTitle.text = displayTitle
        epgTime.text = "Season ${episode.season} - Episode ${episode.episode_num}"
        val description = episode.description?.takeIf { it.isNotBlank() }
            ?: getString(R.string.epg_description_not_available)
        epgDescription.text = description

        updateEpgVisibility()
    }

    private fun updateEpgVisibility() {
        val shouldShow = !isFullscreen && (hasEpgData || epgJob != null || isEpisodeEpgActive)
        epgContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun formatEpgTime(start: Long, end: Long): String {
        if (start <= 0 || end <= 0) return ""
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${formatter.format(Date(start * 1000))} - ${formatter.format(Date(end * 1000))}"
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            fullscreenButton.visibility = View.GONE
            playerView.useController = false
            playerView.layoutParams = playerView.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            playerView.requestLayout()
            fullConstraints.applyTo(mainContainer)
        } else {
            fullscreenButton.visibility = View.VISIBLE
            playerView.layoutParams = playerView.layoutParams.apply { height = defaultPlayerHeight }
            playerView.requestLayout()
            normalConstraints.applyTo(mainContainer)
        }
        updateEpgVisibility()
    }

    private fun showGroups() {
        isEpisodeEpgActive = false
        state = UiState.ShowGroups
        val recentsGroup = buildRecentsGroup()
        val favoritesItems = buildFavoritesItems()
        val displayGroups = listOfNotNull(recentsGroup) + groups
        
        val allItems = mutableListOf<ListItem>()
        
        // Add Favorites header and items if there are any favorites
        if (favoritesItems.isNotEmpty()) {
            allItems.add(ListItem.GroupItem(Group("Favorites", emptyList())))
            allItems.addAll(favoritesItems)
        }
        
        // Add other groups
        allItems.addAll(ListItem.from(displayGroups))
        
        // Add series as items
        allItems.addAll(ListItem.fromSeries(seriesList.sortedBy { it.name.lowercase() }))
        
        adapter.submit(allItems)
        restoreGroupPosition()
        backButton.visibility = View.GONE
        listTitle.setText(R.string.channels_title)
        searchInput.text.clear()
        updateEpgVisibility()
    }

    private fun showChannelsIn(group: Group, position: Int) {
        isEpisodeEpgActive = false
        lastGroupPosition = position
        state = UiState.ShowChannels(group)
        adapter.submit(ListItem.from(group))
        resetListPosition()
        backButton.visibility = View.VISIBLE
        listTitle.text = group.name
        searchInput.text.clear()
        updateEpgVisibility()
    }

    private fun filter(query: String) {
        if (query.isBlank()) {
            showGroups()
            return
        }

        isEpisodeEpgActive = false
        state = UiState.ShowSearchResults
       
        // Search through all channels (live, movies/VOD)
        val filteredChannels = allStreams.filter { it.name.contains(query, ignoreCase = true) }
        val channelItems = filteredChannels.map { ListItem.ChannelItem(it) }
       
        // Search through all series
        val filteredSeries = seriesList.filter { it.name.contains(query, ignoreCase = true) }
        val seriesItems = filteredSeries.map { ListItem.SeriesItem(it) }
       
        // Combine all results
        val allResults = channelItems + seriesItems
        adapter.submit(allResults)
        listTitle.setText(R.string.search_results_title)
        backButton.visibility = View.VISIBLE
        updateEpgVisibility()
    }

    private fun saveRecent(stream: Stream) {
        val key = "recent_stream_ids"
        val existing = prefs.getString(key, "").orEmpty()
        val ids = existing.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
            .toMutableList()
        ids.remove(stream.stream_id)
        ids.add(0, stream.stream_id)
        while (ids.size > 20) ids.removeLast()
        val serialized = ids.joinToString(",")
        prefs.edit().putString(key, serialized).apply()
        if (state is UiState.ShowGroups) {
            showGroups()
        }
    }

    private fun getRecentIds(): List<Int> {
        val existing = prefs.getString("recent_stream_ids", "").orEmpty()
        return existing.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    private fun getFavoriteIds(): Set<String> {
        return prefs.getStringSet("favorite_ids", emptySet()) ?: emptySet()
    }

    private fun toggleFavoriteStream(stream: Stream) {
        val key = "stream_${stream.stream_id}"
        toggleFavorite(key)
    }

    private fun toggleFavoriteSeries(series: SeriesInfo) {
        val key = "series_${series.series_id}"
        toggleFavorite(key)
    }

    private fun toggleFavoriteEpisode(seriesId: Int, season: Int, episode: Episode) {
        val key = "episode_${seriesId}_${season}_${episode.id}"
        toggleFavorite(key)
    }

    private fun toggleFavorite(key: String) {
        val current = prefs.getStringSet("favorite_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        prefs.edit().putStringSet("favorite_ids", current).apply()

        // Refresh UI based on current state
        when (state) {
            is UiState.ShowGroups -> {
                showGroups()
            }
            is UiState.ShowChannels -> {
                val groupState = state as UiState.ShowChannels
                showChannelsIn(groupState.group, lastGroupPosition ?: 0)
            }
            is UiState.ShowSearchResults -> {
                adapter.notifyDataSetChanged()
            }
            is UiState.ShowSeriesSeasons, is UiState.ShowSeasonEpisodes -> {
                adapter.notifyDataSetChanged()
            }
        }
        updateEpgVisibility()
    }

    private fun isFavoriteStream(stream: Stream): Boolean {
        val key = "stream_${stream.stream_id}"
        return getFavoriteIds().contains(key)
    }

    private fun isFavoriteSeries(series: SeriesInfo): Boolean {
        val key = "series_${series.series_id}"
        return getFavoriteIds().contains(key)
    }

    private fun isFavoriteEpisode(seriesId: Int, season: Int, episode: Episode): Boolean {
        val key = "episode_${seriesId}_${season}_${episode.id}"
        return getFavoriteIds().contains(key)
    }
    
    private fun restoreFocusToStream(streamId: Int) {
        for (i in 0 until adapter.itemCount) {
            val viewHolder = channelList.findViewHolderForAdapterPosition(i)
            if (viewHolder?.itemView != null) {
                val item = adapter.items.getOrNull(i)
                if (item is ListItem.ChannelItem && item.stream.stream_id == streamId) {
                    viewHolder.itemView.requestFocus()
                    break
                }
            }
        }
    }

    private fun showSeriesSeasons(series: SeriesInfo) {
        isEpisodeEpgActive = false
        state = UiState.ShowSeriesSeasons(series.series_id)
        backButton.visibility = View.VISIBLE
        listTitle.text = series.name
        searchInput.text.clear()
        showLoading()
        updateEpgVisibility()
        
        // Fetch series details with seasons and episodes
        lifecycleScope.launch {
            val api = XtreamApi(baseUrl, username, password)
            val seriesDetails = withContext(Dispatchers.IO) { api.fetchSeriesDetails(series.series_id) }
            
            if (seriesDetails != null && seriesDetails.seasons.isNotEmpty()) {
                // Update the series in the list
                val updatedList = seriesList.map { 
                    if (it.series_id == series.series_id) seriesDetails else it 
                }
                seriesList = updatedList
                
                val seasonItems = ListItem.fromSeriesSeasons(seriesDetails.series_id, seriesDetails.name, seriesDetails.seasons)
                adapter.submit(seasonItems)
                resetListPosition()
            } else {
                // No seasons found
                adapter.submit(emptyList())
                resetListPosition()
            }
            hideLoading()
        }
    }

    private fun resetListPosition() {
        channelList.post {
            if (adapter.itemCount == 0) return@post
            channelList.scrollToPosition(0)
            (channelList.layoutManager as? LinearLayoutManager)
                ?.findViewByPosition(0)
                ?.requestFocus()
        }
    }

    private fun restoreGroupPosition() {
        val position = lastGroupPosition ?: return
        channelList.post {
            val target = position.coerceIn(0, adapter.itemCount - 1)
            val layoutManager = channelList.layoutManager as? LinearLayoutManager ?: return@post
            layoutManager.scrollToPositionWithOffset(target, 0)

            // Post once more to let layout settle before requesting focus
            channelList.post {
                layoutManager.findViewByPosition(target)?.requestFocus()
            }
        }
    }

    private fun showSeasonEpisodes(seriesId: Int, season: Int) {
        val series = seriesList.find { it.series_id == seriesId } ?: return
        val episodes = series.seasons[season] ?: return
        isEpisodeEpgActive = false
        state = UiState.ShowSeasonEpisodes(seriesId, season)
        val episodeItems = ListItem.fromSeasonEpisodes(seriesId, season, episodes)
        adapter.submit(episodeItems)
        backButton.visibility = View.VISIBLE
        listTitle.text = "${series.name} - Season $season"
        searchInput.text.clear()

        episodes.firstOrNull()?.let { showEpisodeResume(it) }
    }

    private fun playEpisode(seriesId: Int, season: Int, episode: Episode, fromRetry: Boolean = false) {
        if (fromRetry) {
            playbackRetryJob?.cancel()
        } else {
            resetRetryState()
        }
        lastPlaybackAction = { playEpisode(seriesId, season, episode, fromRetry = true) }
        val episodeUrl = buildSeriesUrl(baseUrl, username, password, episode)
        nowPlaying.text = "Now Playing: ${episode.title}"
        val mediaItem = MediaItem.fromUri(episodeUrl)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            play()
        }
        setKeepScreenOn(true)
        playerView.useController = false
        showEpisodeResume(episode)
    }

    private fun schedulePlaybackRetry() {
        val playbackAction = lastPlaybackAction ?: return
        playbackRetryJob?.cancel()
        val initialAttempt = consecutiveErrorCount.coerceAtLeast(1)
        playbackRetryJob = lifecycleScope.launch {
            var attempt = initialAttempt
            while (isActive) {
                val backoffMs = (2000L * attempt).coerceAtMost(10000L)
                delay(backoffMs)
                if (!isActive) break
                clearPlayerBuffer()
                playbackAction()
                attempt++
            }
        }
    }

    private fun resetRetryState() {
        consecutiveErrorCount = 0
        playbackRetryJob?.cancel()
        playbackRetryJob = null
    }

    private fun clearPlayerBuffer() {
        // Stop and clear items to drop any bad buffer state before retrying
        player?.apply {
            stop()
            clearMediaItems()
            seekToDefaultPosition()
        }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun buildSeriesUrl(
        baseUrl: String,
        username: String,
        password: String,
        episode: Episode
    ): String {
        val normalized = baseUrl.trimEnd('/')
        val extension = episode.container_extension?.ifBlank { "mkv" } ?: "mkv"
        val streamUrl = "${normalized}/series/${username}/${password}/${episode.id}.${extension}"
        Log.d("MainActivity", "Playing series episode: $streamUrl")
        return streamUrl
    }

    private fun showLoading() {
        loadingContainer.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingContainer.visibility = View.GONE
    }

    private fun buildFavoritesItems(): List<ListItem> {
        val favorites = getFavoriteIds()
        val items = mutableListOf<ListItem>()
        
        // Add favorite channels/movies
        val favStreams = allStreams.filter { favorites.contains("stream_${it.stream_id}") }
            .sortedBy { it.name.lowercase() }
        items.addAll(favStreams.map { ListItem.ChannelItem(it) })
        
        // Add favorite series
        val favSeries = seriesList.filter { favorites.contains("series_${it.series_id}") }
            .sortedBy { it.name.lowercase() }
        items.addAll(favSeries.map { ListItem.SeriesItem(it) })
        
        return items
    }

    private fun buildRecentsGroup(): Group? {
        val recents = getRecentIds()
        if (recents.isEmpty()) return null
        val streamsById = allStreams.associateBy { it.stream_id }
        val ordered = recents.mapNotNull { streamsById[it] }
        if (ordered.isEmpty()) return null
        return Group("Recent", ordered)
    }

    private fun showParentalControlSettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.parental_control)
        
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Toggle parental control
        val enableCheckBox = CheckBox(this).apply {
            text = getString(R.string.enable_parental_control)
            isChecked = parentalControl.isEnabled()
        }
        container.addView(enableCheckBox)
        
        // Set PIN button
        val setPinButton = Button(this).apply {
            text = getString(R.string.set_pin)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(setPinButton)
        
        // Manage restricted categories button
        val restrictCategoriesButton = Button(this).apply {
            text = getString(R.string.restricted_categories)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(restrictCategoriesButton)
        
        builder.setView(container)
        builder.setPositiveButton(R.string.apply) { _, _ ->
            parentalControl.setEnabled(enableCheckBox.isChecked)
        }
        builder.setNegativeButton(R.string.cancel, null)
        
        val dialog = builder.create()
        dialog.show()
        
        setPinButton.setOnClickListener {
            showPinDialog()
        }
        
        restrictCategoriesButton.setOnClickListener {
            showRestrictCategoriesDialog()
        }
    }

    private fun showPinDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.set_pin)
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 16, 16, 16)
        }
        
        val pinInput = EditText(this).apply {
            hint = getString(R.string.pin_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(pinInput)
        
        val confirmPinInput = EditText(this).apply {
            hint = getString(R.string.confirm_pin_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(confirmPinInput)
        
        builder.setView(container)
        builder.setPositiveButton(R.string.save) { _, _ ->
            val pin = pinInput.text.toString()
            val confirmPin = confirmPinInput.text.toString()
            
            when {
                pin.isEmpty() || confirmPin.isEmpty() -> {
                    AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage(R.string.pin_label)
                        .setPositiveButton("OK", null)
                        .show()
                }
                pin.length < 4 || pin.length > 6 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage(R.string.pin_too_short)
                        .setPositiveButton("OK", null)
                        .show()
                }
                pin != confirmPin -> {
                    AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage(R.string.pin_mismatch)
                        .setPositiveButton("OK", null)
                        .show()
                }
                !pin.all { it.isDigit() } -> {
                    AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage(R.string.pin_invalid_format)
                        .setPositiveButton("OK", null)
                        .show()
                }
                else -> {
                    parentalControl.setPIN(pin)
                    AlertDialog.Builder(this)
                        .setTitle("Success")
                        .setMessage("PIN has been set")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
        builder.setNegativeButton(R.string.cancel, null)
        builder.create().show()
    }

    private fun showRestrictCategoriesDialog() {
        if (groups.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.restricted_categories)
                .setMessage("No categories available")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        val categoryNames = groups.map { it.name }.toTypedArray()
        val categoryIds = groups.map { it.name }.toTypedArray()  // Use name as ID for categories
        val restricted = parentalControl.getRestrictedCategories()
        val checkedItems = BooleanArray(categoryNames.size) { i ->
            restricted.contains(categoryIds[i])
        }
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.select_categories)
        builder.setMultiChoiceItems(categoryNames, checkedItems) { _, which, isChecked ->
            if (isChecked) {
                parentalControl.addRestrictedCategory(categoryIds[which])
            } else {
                parentalControl.removeRestrictedCategory(categoryIds[which])
            }
        }
        builder.setPositiveButton(R.string.apply) { _, _ ->
            // Changes are saved in onCheckedChangeListener
        }
        builder.setNegativeButton(R.string.cancel, null)
        builder.create().show()
    }
}


private sealed class UiState {
    object ShowGroups : UiState()
    data class ShowChannels(val group: Group) : UiState()
    object ShowSearchResults : UiState()
    data class ShowSeriesSeasons(val seriesId: Int) : UiState()
    data class ShowSeasonEpisodes(val seriesId: Int, val season: Int) : UiState()
}

private class ChannelAdapter(
    private val onChannelClick: (Stream) -> Unit,
    private val onGroupClick: (Group, Int) -> Unit,
    private val onSeriesClick: (SeriesInfo) -> Unit,
    private val onSeasonClick: (Int, Int) -> Unit,
    private val onEpisodeClick: (Int, Int, Episode) -> Unit,
    private val onEpisodeFocus: (Episode) -> Unit,
    private val onChannelLongClick: (Stream) -> Unit,
    private val onSeriesLongClick: (SeriesInfo) -> Unit,
    private val onEpisodeLongClick: (Int, Int, Episode) -> Unit,
    private val isStreamFavorite: (Stream) -> Boolean,
    private val isSeriesFavorite: (SeriesInfo) -> Boolean,
    private val isEpisodeFavorite: (Int, Int, Episode) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val items = mutableListOf<ListItem>()

    fun submit(newItems: List<ListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.GroupItem -> 0
            is ListItem.ChannelItem -> 1
            is ListItem.SeriesItem -> 4
            is ListItem.SeasonItem -> 2
            is ListItem.EpisodeItem -> 3
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val view = inflater.inflate(R.layout.item_category, parent, false)
                GroupViewHolder(view, onGroupClick)
            }
            1 -> {
                val view = inflater.inflate(R.layout.item_channel, parent, false)
                ChannelViewHolder(view, onChannelClick, onChannelLongClick, isStreamFavorite)
            }
            4 -> {
                val view = inflater.inflate(R.layout.item_category, parent, false)
                SeriesViewHolder(view, onSeriesClick, onSeriesLongClick, isSeriesFavorite)
            }
            2 -> {
                val view = inflater.inflate(R.layout.item_channel, parent, false)
                SeasonViewHolder(view, onSeasonClick)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_channel, parent, false)
                EpisodeViewHolder(view, onEpisodeClick, onEpisodeFocus, onEpisodeLongClick, isEpisodeFavorite)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.GroupItem -> (holder as GroupViewHolder).bind(item)
            is ListItem.ChannelItem -> (holder as ChannelViewHolder).bind(item)
            is ListItem.SeriesItem -> (holder as SeriesViewHolder).bind(item)
            is ListItem.SeasonItem -> (holder as SeasonViewHolder).bind(item)
            is ListItem.EpisodeItem -> (holder as EpisodeViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

private class GroupViewHolder(
    view: View,
    private val onGroupClick: (Group, Int) -> Unit
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.category_title)
    private var group: Group? = null

    init {
        view.setOnClickListener {
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                group?.let { onGroupClick(it, position) }
            }
        }
    }

    fun bind(item: ListItem.GroupItem) {
        group = item.group
        title.text = item.group.name
    }
}

private class ChannelViewHolder(
    view: View,
    private val onChannelClick: (Stream) -> Unit,
    private val onChannelLongClick: (Stream) -> Unit,
    private val isFavorite: (Stream) -> Boolean
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.channel_title)
    private var stream: Stream? = null

    init {
        view.setOnClickListener {
            stream?.let { onChannelClick(it) }
            // Keep focus on the clicked channel item
            view.requestFocus()
        }
        view.setOnLongClickListener {
            stream?.let { onChannelLongClick(it) }
            // Keep focus on this item after long click
            view.requestFocus()
            true
        }
    }

    fun bind(item: ListItem.ChannelItem) {
        stream = item.stream
        title.text = item.stream.name
        val fav = isFavorite(item.stream)
        val endDrawable = if (fav) R.drawable.ic_star_24 else 0
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, endDrawable, 0)
    }
}

private class SeriesViewHolder(
    view: View,
    private val onSeriesClick: (SeriesInfo) -> Unit,
    private val onSeriesLongClick: (SeriesInfo) -> Unit,
    private val isFavorite: (SeriesInfo) -> Boolean
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.category_title)
    private var series: SeriesInfo? = null

    init {
        view.setOnClickListener {
            series?.let { onSeriesClick(it) }
        }
        view.setOnLongClickListener {
            series?.let { onSeriesLongClick(it) }
            view.requestFocus()
            true
        }
    }

    fun bind(item: ListItem.SeriesItem) {
        series = item.series
        title.text = item.series.name
        val fav = isFavorite(item.series)
        val endDrawable = if (fav) R.drawable.ic_star_24 else 0
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, endDrawable, 0)
    }
}

private class SeasonViewHolder(
    view: View,
    private val onSeasonClick: (Int, Int) -> Unit
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.channel_title)
    private var seriesId: Int = 0
    private var season: Int = 0

    init {
        view.setOnClickListener {
            onSeasonClick(seriesId, season)
            view.requestFocus()
        }
    }

    fun bind(item: ListItem.SeasonItem) {
        seriesId = item.seriesId
        season = item.season_num
        title.text = "Season ${item.season_num} (${item.episodeCount} episodes)"
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
    }
}

private class EpisodeViewHolder(
    view: View,
    private val onEpisodeClick: (Int, Int, Episode) -> Unit,
    private val onEpisodeFocus: (Episode) -> Unit,
    private val onEpisodeLongClick: (Int, Int, Episode) -> Unit,
    private val isFavorite: (Int, Int, Episode) -> Boolean
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.channel_title)
    private var seriesId: Int = 0
    private var season: Int = 0
    private var episode: Episode? = null

    init {
        view.setOnClickListener {
            episode?.let { onEpisodeClick(seriesId, season, it) }
            view.requestFocus()
        }

        view.setOnLongClickListener {
            episode?.let { onEpisodeLongClick(seriesId, season, it) }
            view.requestFocus()
            true
        }

        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                episode?.let { onEpisodeFocus(it) }
            }
        }
    }

    fun bind(item: ListItem.EpisodeItem) {
        seriesId = item.seriesId
        season = item.season
        episode = item.episode
        title.text = "Episode ${item.episode.episode_num}: ${item.episode.title}"
        val fav = isFavorite(seriesId, season, item.episode)
        val endDrawable = if (fav) R.drawable.ic_star_24 else 0
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, endDrawable, 0)

        if (itemView.isFocused) {
            episode?.let { onEpisodeFocus(it) }
        }
    }
}
