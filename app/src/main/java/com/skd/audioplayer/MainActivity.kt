package com.skd.audioplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SearchView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var songsAdapter: SongsAdapter
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSongIndex = -1
    private lateinit var visualizerView: CustomVisualizerView
    private lateinit var playingCardView: CardView
    private lateinit var controlPanel: LinearLayout

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var wakeLock: PowerManager.WakeLock

    private var isShuffleOn = false
    private var isRepeatOneOn = false
    private var isRepeatAllOn = false
    // true = "All Songs" (recyclerView) is visible; false = "Now Playing" is visible
    private var isPlaylistVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge() is the official AndroidX helper (activity 1.8+).
        // It calls setDecorFitsSystemWindows(false) AND sets up correct status-bar
        // / nav-bar colors for all Android versions including 15+.
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        instance = WeakReference(this)
        playingCardView = findViewById(R.id.Playing_Song_Cardview)
        controlPanel    = findViewById(R.id.controlPanel)
        // Start the lifecycle service so onTaskRemoved() fires when user clears the app
        startService(Intent(this, AppLifecycleService::class.java))

        // fitsSystemWindows="true" on rootLayout handles all inset padding
        // automatically — no manual listener needed.

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "MusicPlayer::WakeLock"
        )
        wakeLock.acquire()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songsAdapter = SongsAdapter(emptyList()) { song -> playSong(song) }
        recyclerView.adapter = songsAdapter

        seekBar = findViewById(R.id.seekBar)
        setupSeekBarListener()

        mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener {
                it.start()
                seekBar.max = it.duration
                updateSeekBar()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Error playing song.", Toast.LENGTH_SHORT).show()
                false
            }
        }

        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()

        visualizerView = findViewById(R.id.visualizerView)

        if (hasRequiredPermissions()) {
            loadSongs()
            setupVisualizer()
        } else {
            requestRequiredPermissions()
        }

        // Playback controls
        findViewById<Button>(R.id.pauseResumeButton).setOnClickListener { togglePlaybackSafe() }
        findViewById<Button>(R.id.previousButton).setOnClickListener { playPreviousSong() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { playNextSong() }
        findViewById<Button>(R.id.shuffleButton).setOnClickListener { toggleShuffle() }
        findViewById<Button>(R.id.reapet_button).setOnClickListener { toggleRepeat() }

        val playlistButton = findViewById<Button>(R.id.playlist_button)
        val heading = findViewById<TextView>(R.id.heading)

        playlistButton.setOnClickListener {
            if (isPlaylistVisible) {
                playingCardView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                playlistButton.setBackgroundResource(R.drawable.playlist)
                heading.text = "Now Playing"
                visualizerView.visibility = if (currentSongIndex != -1) View.VISIBLE else View.GONE
                if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
            } else {
                playingCardView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                playlistButton.setBackgroundResource(R.drawable.playing_button)
                heading.text = "All Songs"
                visualizerView.visibility = View.GONE
                if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
            }
            isPlaylistVisible = !isPlaylistVisible
        }

        val searchButton = findViewById<Button>(R.id.search_button)
        val searchCard = findViewById<CardView>(R.id.searchCard)
        val searchView = findViewById<SearchView>(R.id.searchView)

        searchButton.setOnClickListener {
            if (searchCard.visibility == View.VISIBLE) {
                searchCard.visibility = View.GONE
                if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchView.windowToken, 0)
            } else {
                searchCard.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE
                playingCardView.visibility = View.GONE
                visualizerView.visibility = View.GONE
                controlPanel.visibility = View.GONE
                searchView.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(searchView, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = false
            override fun onQueryTextChange(newText: String): Boolean {
                val filtered = songsAdapter.getSongs().filter { song ->
                    song.title.contains(newText, ignoreCase = true) ||
                            song.artist.contains(newText, ignoreCase = true)
                }
                songsAdapter.submitList(filtered)
                return true
            }
        })

        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                searchView.setQuery("", false)
                loadSongs()
            }
        }

        // Back press → move app to background instead of destroying it,
        // so the MediaPlayer and notification stay alive.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    private fun hasRequiredPermissions(): Boolean {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun togglePlaybackSafe() {
        if (currentSongIndex == -1) {
            if (songsAdapter.itemCount > 0) {
                currentSongIndex = 0
                playSong(songsAdapter.getSongs()[0])
            } else {
                Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            }
            return
        }
        togglePlayback()
    }

    private fun setupVisualizer() {
        if (::visualizerView.isInitialized &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            visualizerView.setPlayer(mediaPlayer.audioSessionId)
        }
    }

    private fun loadSongs() {
        val songsList = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    cursor.getLong(albumIdCol)
                ).toString()
                songsList.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown",
                        path = cursor.getString(pathCol) ?: "",
                        albumArtUri = albumArtUri
                    )
                )
            }
        }
        songsList.reverse()
        songsAdapter = SongsAdapter(songsList) { song ->
            currentSongIndex = songsList.indexOf(song)
            playSong(song)
        }
        recyclerView.adapter = songsAdapter
        if (songsList.isNotEmpty()) recyclerView.scrollToPosition(0)
    }

    private fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        val btn = findViewById<Button>(R.id.shuffleButton)
        btn.setBackgroundResource(if (isShuffleOn) R.drawable.shuffle_on else R.drawable.shuffle_off)
        if (isShuffleOn) songsAdapter.shuffleSongs() else loadSongs()
    }

    private fun toggleRepeat() {
        val btn = findViewById<Button>(R.id.reapet_button)
        if (isRepeatAllOn) {
            isRepeatAllOn = false; isRepeatOneOn = false
            btn.setBackgroundResource(R.drawable.repeat)
        } else if (isRepeatOneOn) {
            isRepeatAllOn = true; isRepeatOneOn = false
            btn.setBackgroundResource(R.drawable.repeat_on)
        } else {
            isRepeatOneOn = true
            btn.setBackgroundResource(R.drawable.repeat_one)
        }
        if (isRepeatAllOn) isShuffleOn = false
    }

    internal fun playNextSong() {
        if (songsAdapter.itemCount == 0) return
        when {
            isShuffleOn -> {
                currentSongIndex = (0 until songsAdapter.itemCount).random()
                playSong(songsAdapter.getSongs()[currentSongIndex])
            }
            isRepeatOneOn -> playSong(songsAdapter.getSongs()[currentSongIndex])
            currentSongIndex < songsAdapter.itemCount - 1 -> {
                playSong(songsAdapter.getSongs()[++currentSongIndex])
            }
            isRepeatAllOn -> {
                currentSongIndex = 0
                playSong(songsAdapter.getSongs()[currentSongIndex])
            }
            else -> {
                currentSongIndex = -1
                mediaPlayer.stop()
                findViewById<Button>(R.id.pauseResumeButton).setBackgroundResource(R.drawable.play)
            }
        }
    }

    internal fun playPreviousSong() {
        if (songsAdapter.itemCount == 0) return
        if (currentSongIndex - 1 >= 0) {
            playSong(songsAdapter.getSongs()[--currentSongIndex])
        } else if (isRepeatAllOn) {
            currentSongIndex = songsAdapter.itemCount - 1
            playSong(songsAdapter.getSongs()[currentSongIndex])
        }
    }

    private fun playSong(song: Song) {
        val pauseBtn = findViewById<Button>(R.id.pauseResumeButton)
        val albumImageView = findViewById<ImageView>(R.id.Playing_Song_Imageview)
        val artistView = findViewById<TextView>(R.id.song_artist)
        val heading = findViewById<TextView>(R.id.heading)
        val playlistBtn = findViewById<Button>(R.id.playlist_button)

        try {
            songsAdapter.getSongs().forEach { it.isPlaying = false }
            val index = songsAdapter.getSongs().indexOf(song)
            if (index != -1) {
                songsAdapter.getSongs()[index].isPlaying = true
                songsAdapter.notifyDataSetChanged()
            }

            mediaPlayer.reset()
            val songUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
            )
            mediaPlayer.setDataSource(applicationContext, songUri)
            mediaPlayer.prepareAsync()

            mediaPlayer.setOnPreparedListener {
                it.start()
                seekBar.max = it.duration
                updateSeekBar()
                setupVisualizer()

                val titleView = findViewById<TextView>(R.id.song_title)
                titleView.text = song.title
                titleView.isSelected = true  // enables marquee scrolling
                titleView.setOnClickListener { recyclerView.smoothScrollToPosition(index) }

                pauseBtn.setBackgroundResource(R.drawable.pause)
                albumImageView.setImageURI(Uri.parse(song.albumArtUri))
                if (albumImageView.drawable == null) {
                    albumImageView.setImageResource(R.drawable.audioicon)
                }
                artistView.text = song.artist
            }

            mediaPlayer.setOnCompletionListener { playNextSong() }

            showNotification(song, true)
            if (index != -1) recyclerView.smoothScrollToPosition(index)
            visualizerView.visibility = View.VISIBLE
            playingCardView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            heading.text = "Now Playing"
            playlistBtn.setBackgroundResource(R.drawable.playlist)
            isPlaylistVisible = false   // keep flag in sync with actual UI state
            findViewById<CardView>(R.id.searchCard).visibility = View.GONE
            controlPanel.visibility = View.VISIBLE

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error playing song.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    internal fun togglePlayback() {
        val pauseBtn = findViewById<Button>(R.id.pauseResumeButton)
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            pauseBtn.setBackgroundResource(R.drawable.play)
            visualizerView.visibility = View.GONE
        } else {
            mediaPlayer.start()
            pauseBtn.setBackgroundResource(R.drawable.pause)
            visualizerView.visibility = View.VISIBLE
            updateSeekBar()
        }
        // Update notification to reflect new play/pause state
        if (currentSongIndex != -1) {
            showNotification(songsAdapter.getSongs()[currentSongIndex], mediaPlayer.isPlaying)
        }
    }

    private fun updateSeekBar() {
        if (mediaPlayer.isPlaying) {
            val pos = mediaPlayer.currentPosition
            seekBar.progress = pos
            findViewById<TextView>(R.id.positive_playback_timer).text = formatTime(pos)
            findViewById<TextView>(R.id.negative_playback_timer).text =
                "-${formatTime(mediaPlayer.duration - pos)}"
            handler.postDelayed({ updateSeekBar() }, 1000)
        }
    }

    private fun setupSeekBarListener() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { isUserSeeking = false }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music Player", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification(song: Song, isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return

        // Load album art bitmap (null → system uses small icon only)
        val albumBitmap = try {
            contentResolver.openInputStream(Uri.parse(song.albumArtUri))?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }

        // Tap notification → bring app to foreground
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Helper for broadcast PendingIntents to NotificationActionReceiver
        fun actionIntent(action: String, reqCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                this, reqCode,
                Intent(action, null, this, NotificationActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.audioicon)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(albumBitmap)
            .setContentIntent(openApp)
            .setOngoing(isPlaying)           // non-dismissible while playing
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // show on lock screen
            .setOnlyAlertOnce(true)
            .setSound(null)
            // Three action buttons: previous | play/pause | next
            .addAction(R.drawable.previous, "Previous",
                actionIntent(ACTION_PREVIOUS, 101))
            .addAction(
                if (isPlaying) R.drawable.pause else R.drawable.play,
                if (isPlaying) "Pause" else "Play",
                actionIntent(ACTION_TOGGLE, 102)
            )
            .addAction(R.drawable.next, "Next",
                actionIntent(ACTION_NEXT, 103))
            // MediaStyle shows actions in the collapsed notification row
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mediaPlayer.release()
        notificationManager.cancel(NOTIFICATION_ID)
        visualizerView.releaseVisualizer()
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE
            val idx = permissions.indexOf(storagePermission)
            if (idx != -1 && grantResults[idx] == PackageManager.PERMISSION_GRANTED) {
                loadSongs()
                setupVisualizer()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        const val CHANNEL_ID     = "MusicPlayerChannel"
        const val ACTION_TOGGLE  = "com.skd.audioplayer.ACTION_TOGGLE_PLAYBACK"
        const val ACTION_PREVIOUS = "com.skd.audioplayer.ACTION_PLAY_PREVIOUS"
        const val ACTION_NEXT    = "com.skd.audioplayer.ACTION_PLAY_NEXT"
        const val NOTIFICATION_ID = 1
        var instance: WeakReference<MainActivity>? = null
    }
}
