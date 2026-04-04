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
        setContentView(R.layout.activity_main)

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

        val pauseResumeButton: Button = findViewById(R.id.pauseResumeButton)
        pauseResumeButton.setOnClickListener { togglePlaybackSafe() }

        val previousButton: Button = findViewById(R.id.previousButton)
        previousButton.setOnClickListener { playPreviousSong() }

        val nextButton: Button = findViewById(R.id.nextButton)
        nextButton.setOnClickListener { playNextSong() }

        val shuffleButton: Button = findViewById(R.id.shuffleButton)
        shuffleButton.setOnClickListener { toggleShuffle() }

        val repeatButton: Button = findViewById(R.id.reapet_button)
        repeatButton.setOnClickListener { toggleRepeat() }

        val controlPanel: LinearLayout = findViewById(R.id.controlPanel)
        val playlist_button = findViewById<Button>(R.id.playlist_button)
        val Playing_Song_Cardview = findViewById<CardView>(R.id.Playing_Song_Cardview)
        val heading = findViewById<TextView>(R.id.heading)

        var isPlaylistVisible = true

        playlist_button.setOnClickListener {
            if (isPlaylistVisible) {
                Playing_Song_Cardview.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                playlist_button.setBackgroundResource(R.drawable.playlist)
                heading.text = "Now Playing"
                visualizerView.visibility = View.VISIBLE
                controlPanel.visibility = View.VISIBLE
            } else {
                Playing_Song_Cardview.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                playlist_button.setBackgroundResource(R.drawable.playing_button)
                heading.text = "All Songs"
                visualizerView.visibility = View.GONE
                controlPanel.visibility = View.VISIBLE
            }
            isPlaylistVisible = !isPlaylistVisible
        }

        val searchButton: Button = findViewById(R.id.search_button)
        val searchView: SearchView = findViewById(R.id.searchView)
        val playingCardView: CardView = findViewById(R.id.Playing_Song_Cardview)

        searchView.visibility = View.GONE

        searchButton.setOnClickListener {
            if (searchView.visibility == View.VISIBLE) {
                searchView.visibility = View.GONE
                controlPanel.visibility = View.VISIBLE
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

    // Returns true if both storage and audio permissions are granted
    private fun hasRequiredPermissions(): Boolean {
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
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

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val musicCursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )

        musicCursor?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val pathColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn) ?: "Unknown"
                val path = cursor.getString(pathColumn) ?: ""
                val albumId = cursor.getLong(albumIdColumn)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                songsList.add(Song(id, title, artist, path, albumArtUri))
            }
        }

        songsList.reverse()
        songsAdapter = SongsAdapter(songsList) { song ->
            currentSongIndex = songsList.indexOf(song)
            playSong(song)
        }
        recyclerView.adapter = songsAdapter
        if (songsList.isNotEmpty()) {
            recyclerView.scrollToPosition(0)
        }
    }

    private fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        val shuffleButton: Button = findViewById(R.id.shuffleButton)
        shuffleButton.setBackgroundResource(if (isShuffleOn) R.drawable.shuffle_on else R.drawable.shuffle_off)
        if (isShuffleOn) {
            songsAdapter.shuffleSongs()
        } else {
            loadSongs()
        }
    }

    private fun toggleRepeat() {
        val repeatButton: Button = findViewById(R.id.reapet_button)
        if (isRepeatAllOn) {
            isRepeatAllOn = false
            isRepeatOneOn = false
            repeatButton.setBackgroundResource(R.drawable.repeat)
        } else if (isRepeatOneOn) {
            isRepeatAllOn = true
            isRepeatOneOn = false
            repeatButton.setBackgroundResource(R.drawable.repeat_on)
        } else {
            isRepeatOneOn = true
            repeatButton.setBackgroundResource(R.drawable.repeat_one)
        }
        if (isRepeatAllOn) isShuffleOn = false
    }

    private fun playNextSong() {
        if (songsAdapter.itemCount == 0) return
        if (isShuffleOn) {
            val randomIndex = (0 until songsAdapter.itemCount).random()
            currentSongIndex = randomIndex
            playSong(songsAdapter.getSongs()[randomIndex])
        } else if (isRepeatOneOn) {
            playSong(songsAdapter.getSongs()[currentSongIndex])
        } else if (currentSongIndex < songsAdapter.itemCount - 1) {
            currentSongIndex++
            playSong(songsAdapter.getSongs()[currentSongIndex])
        } else if (isRepeatAllOn) {
            currentSongIndex = 0
            playSong(songsAdapter.getSongs()[currentSongIndex])
        } else {
            currentSongIndex = -1
            mediaPlayer.stop()
            val pauseResumeButton: Button = findViewById(R.id.pauseResumeButton)
            pauseResumeButton.setBackgroundResource(R.drawable.play)
        }
    }

    private fun playPreviousSong() {
        if (songsAdapter.itemCount == 0) return
        val previousIndex = currentSongIndex - 1
        if (previousIndex >= 0) {
            currentSongIndex = previousIndex
            playSong(songsAdapter.getSongs()[currentSongIndex])
        } else if (isRepeatAllOn) {
            currentSongIndex = songsAdapter.itemCount - 1
            playSong(songsAdapter.getSongs()[currentSongIndex])
        }
    }

    private fun playSong(song: Song) {
        val pauseResumeButton: Button = findViewById(R.id.pauseResumeButton)
        val playingSongImageView: ImageView = findViewById(R.id.Playing_Song_Imageview)
        val songArtistTextView: TextView = findViewById(R.id.song_artist)
        val cardview: CardView = findViewById(R.id.Playing_Song_Cardview)
        val heading = findViewById<TextView>(R.id.heading)
        val playlist_button = findViewById<Button>(R.id.playlist_button)

        try {
            songsAdapter.getSongs().forEach { it.isPlaying = false }
            val index = songsAdapter.getSongs().indexOf(song)
            if (index != -1) {
                songsAdapter.getSongs()[index].isPlaying = true
                songsAdapter.notifyDataSetChanged()
            }

            mediaPlayer.reset()

            // Use content URI for reliable playback on all Android versions including 13+
            val songUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )
            mediaPlayer.setDataSource(applicationContext, songUri)
            mediaPlayer.prepareAsync()

            mediaPlayer.setOnPreparedListener {
                it.start()
                seekBar.max = it.duration
                updateSeekBar()
                setupVisualizer()
                findViewById<TextView>(R.id.song_title).apply {
                    text = song.title
                    setOnClickListener { recyclerView.smoothScrollToPosition(index) }
                }
                pauseResumeButton.setBackgroundResource(R.drawable.pause)

                val albumArtUri = Uri.parse(song.albumArtUri)
                playingSongImageView.setImageURI(albumArtUri)
                if (playingSongImageView.drawable == null) {
                    playingSongImageView.setImageResource(R.drawable.audioicon)
                }
                songArtistTextView.text = song.artist
            }

            mediaPlayer.setOnCompletionListener { playNextSong() }

            showNotification(song)
            if (index != -1) recyclerView.smoothScrollToPosition(index)
            visualizerView.visibility = View.VISIBLE
            cardview.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            heading.text = "Now Playing"
            playlist_button.setBackgroundResource(R.drawable.playlist)
            val searchView: SearchView = findViewById(R.id.searchView)
            val controlPanel: LinearLayout = findViewById(R.id.controlPanel)
            searchView.visibility = View.GONE
            controlPanel.visibility = View.VISIBLE

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error playing song.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        return String.format("%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun togglePlayback() {
        val pauseResumeButton: Button = findViewById(R.id.pauseResumeButton)
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            pauseResumeButton.setBackgroundResource(R.drawable.play)
            notificationManager.cancel(NOTIFICATION_ID)
            visualizerView.visibility = View.GONE
        } else {
            mediaPlayer.start()
            pauseResumeButton.setBackgroundResource(R.drawable.pause)
            if (currentSongIndex != -1) {
                showNotification(songsAdapter.getSongs()[currentSongIndex])
            }
            visualizerView.visibility = View.VISIBLE
            updateSeekBar()
        }
    }

    private fun updateSeekBar() {
        if (mediaPlayer.isPlaying) {
            val currentPosition = mediaPlayer.currentPosition
            seekBar.progress = currentPosition
            findViewById<TextView>(R.id.positive_playback_timer).text = formatTime(currentPosition)
            val remaining = mediaPlayer.duration - currentPosition
            findViewById<TextView>(R.id.negative_playback_timer).text = "-${formatTime(remaining)}"
            handler.postDelayed({ updateSeekBar() }, 1000)
        }
    }

    private fun setupSeekBarListener() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { isUserSeeking = false }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player Notification Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showNotification(song: Song) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val notificationLayout = RemoteViews(packageName, R.layout.custom_notification_layout)
        notificationLayout.setTextViewText(R.id.notification_title, song.title)
        notificationLayout.setTextViewText(R.id.notification_artist, song.artist)

        val albumArtUri = Uri.parse(song.albumArtUri)
        try {
            val inputStream = contentResolver.openInputStream(albumArtUri)
            if (inputStream != null) {
                val bitmap = BitmapFactory.decodeStream(inputStream)
                notificationLayout.setImageViewBitmap(R.id.notification_album_art, bitmap)
                inputStream.close()
            } else {
                notificationLayout.setImageViewResource(R.id.notification_album_art, R.drawable.audioicon)
            }
        } catch (e: FileNotFoundException) {
            notificationLayout.setImageViewResource(R.id.notification_album_art, R.drawable.audioicon)
        } catch (e: IOException) {
            notificationLayout.setImageViewResource(R.id.notification_album_art, R.drawable.audioicon)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.audioicon)
            .setCustomContentView(notificationLayout)
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
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE

            val storageIndex = permissions.indexOf(storagePermission)
            val storageGranted = storageIndex != -1 &&
                    grantResults[storageIndex] == PackageManager.PERMISSION_GRANTED

            if (storageGranted) {
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
