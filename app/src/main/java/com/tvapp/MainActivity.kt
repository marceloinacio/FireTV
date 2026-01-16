package com.tvapp

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
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
    private lateinit var listTitle: TextView
    private lateinit var searchInput: EditText

    private lateinit var leftPanel: View
    private lateinit var playerContainer: View
    private lateinit var normalConstraints: ConstraintSet
    private lateinit var fullConstraints: ConstraintSet

    private var player: ExoPlayer? = null
    private var isFullscreen = false
    private var state: UiState = UiState.ShowGroups
    private var groups: List<Group> = emptyList()
    private var allStreams: List<Stream> = emptyList()
    private lateinit var fullscreenButton: ImageButton

    private val adapter = ChannelAdapter(
        onChannelClick = { stream -> startPlayback(stream) },
        onGroupClick = { group -> showChannelsIn(group) },
        onChannelLongClick = { stream -> toggleFavorite(stream) },
        isFavorite = { stream -> getFavoriteIds().contains(stream.stream_id) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this) {
            when (state) {
                is UiState.ShowChannels -> showGroups()
                is UiState.ShowSearchResults -> showGroups()
                else -> finish()
            }
        }

        prefs = getSharedPreferences("xtream_prefs", MODE_PRIVATE)
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
        backButton = findViewById(R.id.back_button)
        listTitle = findViewById(R.id.list_title)
        searchInput = findViewById(R.id.search_input)
        fullscreenButton = findViewById(R.id.fullscreen_button)

        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = adapter

        player = ExoPlayer.Builder(this).build().also { playerView.player = it }
        backButton.setOnClickListener { showGroups() }
        fullscreenButton.setOnClickListener { toggleFullscreen() }
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
            clear(R.id.player_container)
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
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showCredentials() {
        credentialsContainer.visibility = View.VISIBLE
        mainContainer.visibility = View.GONE
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
        lifecycleScope.launch {
            val api = XtreamApi(url, username, password)
            val categories = withContext(Dispatchers.IO) { api.fetchCategories() }
            val streams = withContext(Dispatchers.IO) { api.fetchStreams() }

            allStreams = streams
            groups = buildGroups(categories, streams)
            showGroups()
        }
    }

    private fun buildGroups(categories: List<Category>, streams: List<Stream>): List<Group> {
        val categoryMap = categories.associateBy { it.category_id }
        val grouped = streams.groupBy { it.category_id }
        val sortedCategories = categories.sortedBy { it.category_name.lowercase() }.toMutableList()
        if (grouped.keys.any { it !in categoryMap }) {
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

    private fun startPlayback(stream: Stream) {
        val url = prefs.getString("url", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            showCredentials()
            return
        }
        saveRecent(stream)
        val streamUrl = buildStreamUrl(url, username, password, stream)
        nowPlaying.text = "Now Playing: ${stream.name}"
        val mediaItem = MediaItem.fromUri(streamUrl)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    private fun buildStreamUrl(
        baseUrl: String,
        username: String,
        password: String,
        stream: Stream
    ): String {
        val normalized = baseUrl.trimEnd('/')
        val extension = stream.container_extension?.ifBlank { "m3u8" } ?: "m3u8"
        val streamUrl = "${normalized}/live/${username}/${password}/${stream.stream_id}.${extension}"
        Log.d("MainActivity", "Playing stream: $streamUrl")
        return streamUrl
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            fullConstraints.applyTo(mainContainer)
        } else {
            normalConstraints.applyTo(mainContainer)
        }
    }

    private fun showGroups() {
        state = UiState.ShowGroups
        val recentsGroup = buildRecentsGroup()
        val favoritesGroup = buildFavoritesGroup()
        val displayGroups = listOfNotNull(recentsGroup, favoritesGroup) + groups
        adapter.submit(ListItem.from(displayGroups))
        backButton.visibility = View.GONE
        listTitle.setText(R.string.channels_title)
        searchInput.text.clear()
    }

    private fun showChannelsIn(group: Group) {
        state = UiState.ShowChannels(group)
        adapter.submit(ListItem.from(group))
        backButton.visibility = View.VISIBLE
        listTitle.text = group.name
        searchInput.text.clear()
    }

    private fun filter(query: String) {
        if (query.isBlank()) {
            showGroups()
            return
        }

        state = UiState.ShowSearchResults
        val filteredChannels = groups.flatMap { it.channels }.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submit(filteredChannels.map { ListItem.ChannelItem(it) })
        listTitle.setText(R.string.search_results_title)
        backButton.visibility = View.VISIBLE
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

    private fun getFavoriteIds(): Set<Int> {
        val set = prefs.getStringSet("favorite_stream_ids", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun toggleFavorite(stream: Stream) {
        val key = "favorite_stream_ids"
        val current = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        val idStr = stream.stream_id.toString()
        if (current.contains(idStr)) {
            current.remove(idStr)
        } else {
            current.add(idStr)
        }
        prefs.edit().putStringSet(key, current).apply()

        when (state) {
            is UiState.ShowGroups -> showGroups()
            is UiState.ShowChannels, is UiState.ShowSearchResults -> adapter.notifyDataSetChanged()
        }
    }

    private fun buildFavoritesGroup(): Group {
        val favorites = getFavoriteIds()
        val favStreams = allStreams.filter { favorites.contains(it.stream_id) }
            .sortedBy { it.name.lowercase() }
        return Group("Favorites", favStreams)
    }

    private fun buildRecentsGroup(): Group? {
        val recents = getRecentIds()
        if (recents.isEmpty()) return null
        val streamsById = allStreams.associateBy { it.stream_id }
        val ordered = recents.mapNotNull { streamsById[it] }
        if (ordered.isEmpty()) return null
        return Group("Recent", ordered)
    }
}

private sealed class UiState {
    object ShowGroups : UiState()
    data class ShowChannels(val group: Group) : UiState()
    object ShowSearchResults : UiState()
}

private class ChannelAdapter(
    private val onChannelClick: (Stream) -> Unit,
    private val onGroupClick: (Group) -> Unit,
    private val onChannelLongClick: (Stream) -> Unit,
    private val isFavorite: (Stream) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<ListItem>()

    fun submit(newItems: List<ListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.GroupItem -> 0
            is ListItem.ChannelItem -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val view = inflater.inflate(R.layout.item_category, parent, false)
            GroupViewHolder(view, onGroupClick)
        } else {
            val view = inflater.inflate(R.layout.item_channel, parent, false)
            ChannelViewHolder(view, onChannelClick, onChannelLongClick, isFavorite)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.GroupItem -> (holder as GroupViewHolder).bind(item)
            is ListItem.ChannelItem -> (holder as ChannelViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

private class GroupViewHolder(
    view: View,
    private val onGroupClick: (Group) -> Unit
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.category_title)
    private var group: Group? = null

    init {
        view.setOnClickListener {
            group?.let { onGroupClick(it) }
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
        }
        view.setOnLongClickListener {
            stream?.let { onChannelLongClick(it) }
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
