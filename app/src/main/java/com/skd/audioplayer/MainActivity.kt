package com.skd.audioplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
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
import android.widget.RemoteViews
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
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.FileNotFoundException
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var songsAdapter: SongsAdapter
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var currentSongIndex = -1
    private lateinit var visualizerView: CustomVisualizerView

    private val NOTIFICATION_ID = 1
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var wakeLock: PowerManager.WakeLock

    private var isShuffleOn = false
    private var isRepeatOneOn = false
    private var isRepeatAllOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge() is the official AndroidX helper (activity 1.8+).
        // It calls setDecorFitsSystemWindows(false) AND sets up correct status-bar
        // / nav-bar colors for all Android versions including 15+.
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)

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

        val controlPanel = findViewById<LinearLayout>(R.id.controlPanel)
        val playlistButton = findViewById<Button>(R.id.playlist_button)
        val playingCardView = findViewById<CardView>(R.id.Playing_Song_Cardview)
        val heading = findViewById<TextView>(R.id.heading)
        var isPlaylistVisible = true

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
        val searchView = findViewById<SearchView>(R.id.searchView)
        searchView.visibility = View.GONE

        searchButton.setOnClickListener {
            if (searchView.visibility == View.VISIBLE) {
                searchView.visibility = View.GONE
                if (currentSongIndex != -1) controlPanel.visibility = View.VISIBLE
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchView.windowToken, 0)
            } else {
                searchView.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE
                playingCardView.visibility = View.GONE
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

    private fun playNextSong() {
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

    private fun playPreviousSong() {
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
        val cardView = findViewById<CardView>(R.id.Playing_Song_Cardview)
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

            showNotification(song)
            if (index != -1) recyclerView.smoothScrollToPosition(index)
            visualizerView.visibility = View.VISIBLE
            cardView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            heading.text = "Now Playing"
            playlistBtn.setBackgroundResource(R.drawable.playlist)
            findViewById<SearchView>(R.id.searchView).visibility = View.GONE
            findViewById<LinearLayout>(R.id.controlPanel).visibility = View.VISIBLE

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error playing song.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun togglePlayback() {
        val pauseBtn = findViewById<Button>(R.id.pauseResumeButton)
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            pauseBtn.setBackgroundResource(R.drawable.play)
            notificationManager.cancel(NOTIFICATION_ID)
            visualizerView.visibility = View.GONE
        } else {
            mediaPlayer.start()
            pauseBtn.setBackgroundResource(R.drawable.pause)
            if (currentSongIndex != -1) showNotification(songsAdapter.getSongs()[currentSongIndex])
            visualizerView.visibility = View.VISIBLE
            updateSeekBar()
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
                CHANNEL_ID, "Music Player", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification(song: Song) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val layout = RemoteViews(packageName, R.layout.custom_notification_layout).apply {
            setTextViewText(R.id.notification_title, song.title)
            setTextViewText(R.id.notification_artist, song.artist)
            try {
                val input = contentResolver.openInputStream(Uri.parse(song.albumArtUri))
                if (input != null) {
                    setImageViewBitmap(R.id.notification_album_art, BitmapFactory.decodeStream(input))
                    input.close()
                } else {
                    setImageViewResource(R.id.notification_album_art, R.drawable.audioicon)
                }
            } catch (e: FileNotFoundException) {
                setImageViewResource(R.id.notification_album_art, R.drawable.audioicon)
            } catch (e: IOException) {
                setImageViewResource(R.id.notification_album_art, R.drawable.audioicon)
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.audioicon)
            .setCustomContentView(layout)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
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
        private const val CHANNEL_ID = "MusicPlayerChannel"
    }
}
