package com.amazon.tv.leanbacklauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.LoaderManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_NO_CREATE
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.Loader
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.location.Location
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.tv.TvContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.OnHierarchyChangeListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import okio.Buffer
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.res.ResourcesCompat
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.core.view.isNotEmpty
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.amazon.tv.firetv.leanbacklauncher.apps.AppInfoActivity
import com.amazon.tv.firetv.leanbacklauncher.apps.RowPreferences
import com.amazon.tv.firetv.leanbacklauncher.apps.RowPreferences.getWeatherApiKey
import com.amazon.tv.firetv.leanbacklauncher.apps.RowType
import com.amazon.tv.firetv.tvrecommendations.NotificationListenerMonitor
import com.amazon.tv.leanbacklauncher.SearchOrbView.SearchLaunchListener
import com.amazon.tv.leanbacklauncher.animation.AnimatorLifecycle
import com.amazon.tv.leanbacklauncher.animation.AnimatorLifecycle.OnAnimationFinishedListener
import com.amazon.tv.leanbacklauncher.animation.EditModeMassFadeAnimator
import com.amazon.tv.leanbacklauncher.animation.EditModeMassFadeAnimator.EditMode
import com.amazon.tv.leanbacklauncher.animation.ForwardingAnimatorSet
import com.amazon.tv.leanbacklauncher.animation.LauncherDismissAnimator
import com.amazon.tv.leanbacklauncher.animation.LauncherLaunchAnimator
import com.amazon.tv.leanbacklauncher.animation.LauncherPauseAnimator
import com.amazon.tv.leanbacklauncher.animation.LauncherReturnAnimator
import com.amazon.tv.leanbacklauncher.animation.MassSlideAnimator
import com.amazon.tv.leanbacklauncher.animation.NotificationLaunchAnimator
import com.amazon.tv.leanbacklauncher.animation.ParticipatesInLaunchAnimation
import com.amazon.tv.leanbacklauncher.apps.AppsManager.Companion.getInstance
import com.amazon.tv.leanbacklauncher.apps.BannerView
import com.amazon.tv.leanbacklauncher.apps.OnEditModeChangedListener
import com.amazon.tv.leanbacklauncher.clock.ClockView
import com.amazon.tv.leanbacklauncher.logging.LeanbackLauncherEventLogger
import com.amazon.tv.leanbacklauncher.notifications.HomeScreenView
import com.amazon.tv.leanbacklauncher.notifications.NotificationCardView
import com.amazon.tv.leanbacklauncher.notifications.NotificationRowView
import com.amazon.tv.leanbacklauncher.notifications.NotificationRowView.NotificationRowListener
import com.amazon.tv.leanbacklauncher.notifications.NotificationsAdapter
import com.amazon.tv.leanbacklauncher.settings.LegacyHomeScreenSettingsActivity
import com.amazon.tv.leanbacklauncher.settings.SettingsActivity
import com.amazon.tv.leanbacklauncher.util.OpenWeatherIcons
import com.amazon.tv.leanbacklauncher.util.Partner
import com.amazon.tv.leanbacklauncher.util.Permission
import com.amazon.tv.leanbacklauncher.util.TvSearchIconLoader
import com.amazon.tv.leanbacklauncher.util.TvSearchSuggestionsLoader
import com.amazon.tv.leanbacklauncher.util.Util
import com.amazon.tv.leanbacklauncher.util.breath
import com.amazon.tv.leanbacklauncher.wallpaper.LauncherWallpaper
import com.amazon.tv.leanbacklauncher.wallpaper.WallpaperInstaller
import com.amazon.tv.leanbacklauncher.widget.EditModeView
import com.amazon.tv.leanbacklauncher.widget.EditModeView.OnEditModeUninstallPressedListener
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.interaapps.localweather.LocalWeather
import de.interaapps.localweather.Weather
import de.interaapps.localweather.utils.Lang
import de.interaapps.localweather.utils.LocationFailedEnum
import de.interaapps.localweather.utils.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.String.format
import java.lang.ref.WeakReference
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity(), OnEditModeChangedListener,
    OnEditModeUninstallPressedListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val FIRST_POSITION = 0
        private const val UNINSTALL_CODE = 321
        const val PERMISSIONS_REQUEST_LOCATION = 99
        const val PERMISSIONS_REQUEST_RECORD_AUDIO = 100
        const val PERMISSIONS_REQUEST_READ_PHONE_STATE = 101
        val JSONFILE = LauncherApp.context.cacheDir?.absolutePath + "/weather.json"

        // 语音识别API配置 (使用百度语音识别API - 国内免费)
        // 注册地址: https://console.bce.baidu.com/ai/#/ai/speech/overview/index
        // 创建应用后获取 API Key 和 Secret Key
        private const val BAIDU_API_KEY = "YOUR_BAIDU_API_KEY" // TODO: 替换为你的百度API Key
        private const val BAIDU_SECRET_KEY = "YOUR_BAIDU_SECRET_KEY" // TODO: 替换为你的百度Secret Key
        private const val BAIDU_TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val BAIDU_ASR_URL = "https://vop.baidu.com/server_api"

        // 录音配置
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val MAX_RECORD_DURATION = 8000L // 最大录音时长8秒
        private const val LONG_PRESS_DURATION = 2000L // 长按2秒触发BLE扫描

        fun isMediaKey(keyCode: Int): Boolean {
            return when (keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MUTE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_RECORD -> true

                else -> false
            }
        }

        private fun getBoundsOnScreen(v: View, epicenter: Rect) {
            val location = IntArray(2)
            v.getLocationOnScreen(location)
            epicenter.left = location[0]
            epicenter.top = location[1]
            epicenter.right = epicenter.left + (v.width.toFloat() * v.scaleX).roundToInt()
            epicenter.bottom = epicenter.top + (v.height.toFloat() * v.scaleY).roundToInt()
        }
    }

    // Weather
    private var localWeather: LocalWeather? = null

    // Weather constants
    private val gson by lazy { GsonBuilder().setPrettyPrinting().create() }
    private val maxCacheAge = TimeUnit.MINUTES.toMillis(30) // 30 minutes

    // Weather Animation constants
    private val showCycleDur: Long = TimeUnit.SECONDS.toMillis(10) // 10 seconds
    private val fadeInDur: Long = 30L // milliseconds
    private val fadeOutDur: Long = 500L
    private var weatherAnimationJob: Job? = null

    // ========== 语音助手相关变量 ==========
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val okHttpClient by lazy { OkHttpClient.Builder().build() }
    private var voiceKeyDownTime = 0L // 语音键按下时间（用于长按检测）
    

    // =======================================

    // Core components
    private val mHandler: Handler = MainActivityMessageHandler(this)
    private val mIdleListeners = mutableListOf<IdleListener>()
    private val mNotifListener = NotificationListenerImpl()
    private val mPackageReplacedReceiver = PackageReplacedReceiver()
    private val mHomeRefreshReceiver = HomeRefreshReceiver()

    // Views
    var editModeView: EditModeView? = null
        private set
    var wallpaperView: LauncherWallpaper? = null
        private set
    private var mListView: VerticalGridView? = null
    private var mHomeScreenView: HomeScreenView? = null
    private var mNotificationsView: NotificationRowView? = null
    private var mAppWidgetHostView: AppWidgetHostView? = null

    // Adapters
    var homeAdapter: HomeScreenAdapter? = null
        private set
    private var mRecommendationsAdapter: NotificationsAdapter? = null

    // Services
    private var mAppWidgetHost: AppWidgetHost? = null
    private var mAppWidgetManager: AppWidgetManager? = null
    private var mContentResolver: ContentResolver? = null
    private var mEventLogger: LeanbackLauncherEventLogger? = null
    private var mAccessibilityManager: AccessibilityManager? = null
    private var mScrollManager: HomeScrollManager? = null

    // State variables
    var isInEditMode = false
        private set
    private var mDelayFirstRecommendationsVisible = true
    private var mFadeDismissAndSummonAnimations = false
    private var mIsIdle = false
    private var mKeepUiReset = false
    private var mUserInteracted = false
    private var mShyMode = false
    private var mStartingEditMode = false
    private var mUninstallRequested = false
    private var mResetAfterIdleEnabled = false

    private val mIdlePeriod: Int by lazy {
        resources.getInteger(R.integer.idle_period)
    }
    private val mResetPeriod: Int by lazy {
        resources.getInteger(R.integer.reset_period)
    }

    // Animations
    private val mEditModeAnimation = AnimatorLifecycle()
    private val mLaunchAnimation = AnimatorLifecycle()
    private val mPauseAnimation = AnimatorLifecycle()
    private val mMoveTaskToBack = Runnable {
        if (!moveTaskToBack(true)) {
            mLaunchAnimation.reset()
        }
    }

    private val mRefreshHomeAdapter = Runnable {
        homeAdapter?.refreshAdapterData()
    }

    interface IdleListener {
        fun onIdleStateChange(z: Boolean)
        fun onVisibilityChange(z: Boolean)
    }

    // Inner classes for better organization
    private inner class NotificationListenerImpl : NotificationRowListener {
        private var mHandler: Handler? = null
        private val mSelectFirstRecommendationRunnable = Runnable {
            mNotificationsView?.takeIf { (it.adapter?.itemCount ?: 0) > 0 }
                ?.setSelectedPositionSmooth(FIRST_POSITION)
        }

        override fun onBackgroundImageChanged(imageUri: String?, signature: String?) {
            wallpaperView?.onBackgroundImageChanged(imageUri, signature)
        }

        override fun onSelectedRecommendationChanged(position: Int) {
            if (mKeepUiReset && mAccessibilityManager?.isEnabled != true && position > FIRST_POSITION) {
                mHandler = mHandler ?: Handler()
                mHandler?.post(mSelectFirstRecommendationRunnable)
            }
        }
    }

    private inner class PackageReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.data?.toString()?.takeIf {
                it.contains("${context?.packageName}.recommendations")
            }?.let {
                Log.d(TAG, "Recommendations Service updated, reconnecting.")
                homeAdapter?.onReconnectToRecommendationsService()
            }
        }
    }

    private inner class HomeRefreshReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra("RefreshHome", false) == true) {
                Log.d(TAG, "RESTART HOME")
                recreate()
            }
        }
    }

    private class MainActivityMessageHandler(activity: MainActivity) : Handler() {
        private val activityRef = WeakReference(activity)
        override fun handleMessage(msg: Message) {
            activityRef.get()?.let { activity ->
                var idle = true
                when (msg.what) {
                    1, 2 -> {
                        val mainActivity = activity
                        if (msg.what != 1) {
                            idle = false
                        }
                        mainActivity.mIsIdle = idle
                        var i = 0
                        while (i < activity.mIdleListeners.size) {
                            activity.mIdleListeners[i].onIdleStateChange(activity.mIsIdle)
                            i++
                        }
                        return
                    }

                    3 -> {
                        if (activity.mResetAfterIdleEnabled) {
                            activity.mKeepUiReset = true
                            activity.resetLauncherState(true)
                            //if (BuildConfig.DEBUG) Log.d(TAG, "msg(3) resetLauncherState(smooth: true)")
                            return
                        }
                        return
                    }

                    4 -> {
                        activity.onNotificationRowStateUpdate(msg.arg1)
                        //if (BuildConfig.DEBUG) Log.d(TAG, "msg(4) onNotificationRowStateUpdate(${msg.arg1})")
                        return
                    }

                    5 -> {
                        activity.homeAdapter?.onUiVisible()
                        //if (BuildConfig.DEBUG) Log.d(TAG, "msg(5) onUiVisible()")
                        return
                    }

                    6 -> {
                        activity.addWidget(true)
                        //if (BuildConfig.DEBUG) Log.d(TAG, "msg(6) addWidget(refresh: true)")
                        return
                    }

                    7 -> {
                        activity.checkLaunchPointPositions()
                        //if (BuildConfig.DEBUG) Log.d(TAG, "msg(7) checkLaunchPointPositions()")
                        return
                    }

                    else -> TODO()
                }
            }
        }
    }

    private val mSearchIconCallbacks: LoaderManager.LoaderCallbacks<Drawable> =
        object : LoaderManager.LoaderCallbacks<Drawable> {
            override fun onCreateLoader(id: Int, args: Bundle?): Loader<Drawable> {
                return TvSearchIconLoader(this@MainActivity.applicationContext)
            }

            override fun onLoadFinished(loader: Loader<Drawable>, data: Drawable?) {
                homeAdapter?.onSearchIconUpdate(data)
            }

            override fun onLoaderReset(loader: Loader<Drawable>) {
                homeAdapter?.onSearchIconUpdate(null)
            }
        }

    private val mSearchSuggestionsCallbacks: LoaderManager.LoaderCallbacks<Array<String>> =
        object : LoaderManager.LoaderCallbacks<Array<String>> {
            override fun onCreateLoader(id: Int, args: Bundle?): Loader<Array<String>> {
                return TvSearchSuggestionsLoader(this@MainActivity.applicationContext)
            }

            override fun onLoadFinished(loader: Loader<Array<String>>, data: Array<String>?) {
                homeAdapter?.onSuggestionsUpdate(data)
            }

            override fun onLoaderReset(loader: Loader<Array<String>>) {
                homeAdapter?.onSuggestionsUpdate(emptyArray())
            }
        }

    @SuppressLint("WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mContentResolver = contentResolver

        if (mRecommendationsAdapter == null) {
            mRecommendationsAdapter = NotificationsAdapter(this)
        }
        val appContext = applicationContext
        setContentView(R.layout.activity_main)

        if (Partner.get(this).showLiveTvOnStartUp() && checkFirstRunAfterBoot()) {
            val tvIntent = Intent("android.intent.action.VIEW", TvContract.buildChannelUri(0))
            tvIntent.putExtra("com.google.android.leanbacklauncher.extra.TV_APP_ON_BOOT", true)
            if (packageManager.queryIntentActivities(tvIntent, 1).isNotEmpty()) {
                startActivity(tvIntent)
                finish()
            }
        }
        // android O fix bug orientation
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // overlay permissions request on M+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
                try {
                    startActivityForResult(intent, 0)
                } catch (_: Exception) {
                }
            }
        }
        // network monitor (request from HomeScreenAdapter)
        Permission.isLocationPermissionGranted(this)

        // FIXME: focus issues
        // mAccessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        editModeView = findViewById<EditModeView?>(R.id.edit_mode_view)?.apply {
            setUninstallListener(this@MainActivity)
        }

        wallpaperView = findViewById(R.id.background_container)
        mAppWidgetManager = AppWidgetManager.getInstance(appContext)

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS))
            mAppWidgetHost = AppWidgetHost(this, 123)



        mListView = findViewById(R.id.main_list_view)
        mListView?.apply {
            setHasFixedSize(true)
            windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
            windowAlignmentOffset =
                resources.getDimensionPixelOffset(R.dimen.home_screen_selected_row_alignment)
            windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
            itemAlignmentOffset = 0
            itemAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED

            mScrollManager = HomeScrollManager(this@MainActivity, this).apply {
                addHomeScrollListener(wallpaperView!!)
            }
            homeAdapter = HomeScreenAdapter(
                this@MainActivity,
                mScrollManager!!,
                mRecommendationsAdapter,
                editModeView!!
            ).apply {
                setOnEditModeChangedListener(this@MainActivity)
            }
            setItemViewCacheSize(homeAdapter!!.itemCount)
            adapter = homeAdapter

            val notifIndex = homeAdapter?.getRowIndex(1) // RowType.NOTIFICATIONS
            if (notifIndex != null && notifIndex != -1) {
                selectedPosition = notifIndex
            }
            val rAdapter = homeAdapter?.recommendationsAdapter?.apply {
                addIdleListener(this)
            }
            setAnimateChildLayout(false)
            setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int
                ) {
                    homeAdapter?.onChildViewHolderSelected(parent, child, position)
                }
            })
            setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View, child: View) {
                    var tag = 0
                    if (child.tag is Int) {
                        tag = child.tag as Int
                    }
                    when (tag) {
                        0 -> {
                            if (child is SearchOrbView) {
                                child.setLaunchListener(object : SearchLaunchListener {
                                    override fun onSearchLaunched() {
                                        setShyMode(shy = true, changeWallpaper = true)
                                    }
                                })
                            }
                            addWidget(false)
                        }

                        1, 2 -> {
                            mHomeScreenView = child.findViewById(R.id.home_screen_messaging)
                            mHomeScreenView?.let {
                                val homeScreenMessaging = it.homeScreenMessaging
                                if (tag == 1) {
                                    rAdapter?.setNotificationRowViewFlipper(homeScreenMessaging)
                                    mNotificationsView = it.notificationRow
                                    mNotificationsView?.setListener(mNotifListener)
                                }
                                homeScreenMessaging.setListener { state ->
                                    mHandler.sendMessageDelayed(
                                        mHandler.obtainMessage(4, state, 0),
                                        500
                                    )
                                    if (state == 0 && mDelayFirstRecommendationsVisible) {
                                        mDelayFirstRecommendationsVisible = false
                                        mHandler.sendEmptyMessageDelayed(5, 1500)
                                    }
                                }
                            }
                        }
                    }
                    if (child is IdleListener && !mIdleListeners.contains(child)) {
                        addIdleListener(child as IdleListener)
                    }
                }

                override fun onChildViewRemoved(parent: View, child: View) {}
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    mScrollManager?.onScrolled(dy, currentScrollPos)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    mScrollManager?.onScrollStateChanged(newState)
                }
            })
        }

        mShyMode = true
        setShyMode(shy = !mShyMode, changeWallpaper = true)

        mFadeDismissAndSummonAnimations = resources.getBoolean(R.bool.app_launch_animation_fade)
        mKeepUiReset = true
        homeAdapter?.onInitUi()
        mEventLogger = LeanbackLauncherEventLogger.getInstance(appContext)

        // register package change receiver
        val filter = IntentFilter().apply {
            addAction("android.intent.action.PACKAGE_REPLACED")
            addAction("android.intent.action.PACKAGE_ADDED")
            addDataScheme("package")
        }
        registerReceiver(mPackageReplacedReceiver, filter)
        // regiser RefreshHome broadcast ACTION com.amazon.tv.leanbacklauncher.MainActivity
        registerReceiver(mHomeRefreshReceiver, IntentFilter(this.javaClass.name))

        loaderManager.initLoader(0, null, mSearchIconCallbacks)
        loaderManager.initLoader(1, null, mSearchSuggestionsCallbacks)

        // start notification listener monitor
        if (RowPreferences.areRecommendationsEnabled(this) && LauncherApp.inForeground)
            startService(Intent(this, NotificationListenerMonitor::class.java))

        // fix int options migrate
        RowPreferences.fixRowPrefs()

        // LocalWeather https://github.com/interaapps/LocalWeather-Android
        if (RowPreferences.isWeatherEnabled(this)) {
            localWeather = LocalWeather(
                this@MainActivity,
                getWeatherApiKey(this)
            )
            // initializeWeather() // already in addWidget()
        }
    }



    public override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onDestroy()")
        mHandler.removeMessages(3)
        super.onDestroy()
        homeAdapter?.let {
            it.onStopUi()
            it.unregisterReceivers()
        }
        getInstance(applicationContext)?.onDestroy()
        unregisterReceiver(mPackageReplacedReceiver)
        unregisterReceiver(mHomeRefreshReceiver)
    }

    override fun onUserInteraction() {
        mHandler.removeMessages(3)
        mKeepUiReset = false
        if (hasWindowFocus()) {
            mHandler.removeMessages(1)
            mUserInteracted = true
            if (mIsIdle) {
                mHandler.sendEmptyMessage(2)
            }
            mHandler.sendEmptyMessageDelayed(1, mIdlePeriod.toLong())
        }
        mHandler.sendEmptyMessageDelayed(3, mResetPeriod.toLong())
    }

    private fun addIdleListener(listener: IdleListener) {
        mIdleListeners.add(listener)
        listener.onVisibilityChange(true)
        listener.onIdleStateChange(mIsIdle)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        when {
            isInEditMode -> {
                editModeView?.onBackPressed()
            }

            mLaunchAnimation.isRunning -> {
                mLaunchAnimation.cancel()
            }

            mLaunchAnimation.isPrimed -> {
                mLaunchAnimation.reset()
            }

            else -> {
                if (mLaunchAnimation.isFinished) {
                    mLaunchAnimation.reset()
                }
                dismissLauncher()
            }
        }
    }

//    override fun onBackgroundVisibleBehindChanged(visible: Boolean) {
//        setShyMode(shy = !visible, changeWallpaper = true)
//    }

    override fun onEditModeChanged(z: Boolean) {
        if (isInEditMode == z) {
            return
        }
        if (mAccessibilityManager?.isEnabled == true) {
            setEditMode(editMode = z, useAnimation = false)
        } else {
            setEditMode(editMode = z, useAnimation = true)
        }
    }

    override fun onUninstallPressed(packageName: String?) {
        if (packageName != null && !mUninstallRequested) {
            mUninstallRequested = true
            val uninstallIntent =
                Intent("android.intent.action.UNINSTALL_PACKAGE", "package:$packageName".toUri())
            uninstallIntent.putExtra("android.intent.extra.RETURN_RESULT", true)
            startActivityForResult(uninstallIntent, UNINSTALL_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (RowPreferences.isWeatherEnabled(this))
            localWeather?.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UNINSTALL_CODE && resultCode != 0) {
            if (resultCode == -1) {
                editModeView?.uninstallComplete()
            } else if (resultCode == 1) {
                editModeView?.uninstallFailure()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (RowPreferences.isWeatherEnabled(this))
            localWeather?.onRequestPermissionResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Agree location permission")
                    recreate()
                } else {
                    Log.i(TAG, "Not agree location permission")
                    LauncherApp.toast(R.string.location_note, true)
                }
            }
            PERMISSIONS_REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Agree record audio permission")
                    startRecording()
                } else {
                    Log.i(TAG, "Not agree record audio permission")
                    Toast.makeText(this, "需要录音权限才能使用语音助手", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initializeWeather() {
        localWeather?.let { lw ->
            setupWeatherDefaults(lw)

            if (shouldUseCurrentLocation()) {
                setupLocationBasedWeather(lw)
            } else {
                setupManualLocationWeather(lw)
            }

            setupWeatherCallbacks(lw)
        }
    }

    private fun setupWeatherDefaults(lw: LocalWeather) {
        val ul = Locale.getDefault().isO3Language
        lw.lang = when {
            ul.equals("rus", true) -> Lang.RUSSIAN
            ul.equals("ukr", true) -> Lang.UKRAINIAN
            ul.equals("ita", true) -> Lang.ITALIAN
            ul.equals("fra", true) -> Lang.FRENCH
            ul.equals("esp", true) -> Lang.SPANISH
            ul.equals("deu", true) -> Lang.GERMAN
            else -> Lang.ENGLISH
        }
        lw.unit = if (RowPreferences.isImperialUnits(this)) Units.IMPERIAL else Units.METRIC
    }

    private fun shouldUseCurrentLocation(): Boolean {
        return RowPreferences.isUseLocationEnabled(this) // && !Util.isAmazonDev(this)
    }

    private fun setupLocationBasedWeather(lw: LocalWeather) {
        if (Util.isAmazonDev(this)) {
            lw.useCurrentLocation = false
            fetchGeoIPFallback(lw)
        } else {
            lw.useCurrentLocation = true
            lw.updateCurrentLocation = true
            lw.updateLocationInterval = TimeUnit.MINUTES.toMillis(10) // FIXME: no updates
        }
    }

    private fun setupManualLocationWeather(lw: LocalWeather) {
        try {
            lw.useCurrentLocation = false
            RowPreferences.getUserLocation(this)?.takeIf { it.isNotBlank() }?.let { userLoc ->
                when {
                    userLoc.isDigitsOnly() -> handleCityIdWeather(lw, userLoc)
                    isCoordinateLocation(userLoc) -> handleCoordinateWeather(lw, userLoc)
                    else -> handleCityNameWeather(lw, userLoc)
                }
            } ?: run {
//                if (Util.isAmazonDev(this)) {
//                    fetchGeoIPFallback(lw)
//                } else {
                LauncherApp.toast(R.string.user_location_warning, true)
//                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up manual location weather", e)
        }
    }

    private fun isCoordinateLocation(location: String): Boolean {
        val parts = location.split(", ")
        return parts.size == 2 &&
                parts.first().toDoubleOrNull() != null &&
                parts.last().toDoubleOrNull() != null
    }

    private fun handleCityIdWeather(lw: LocalWeather, cityId: String) {
        if (isWeatherCacheValid) {
            readJsonWeather(JSONFILE)
        } else {
            lw.fetchCurrentWeatherByCityId(cityId)
        }
    }

    private fun handleCoordinateWeather(lw: LocalWeather, coords: String) {
        val parts = coords.split(", ")
        val lat = parts.first().toDouble()
        val lon = parts.last().toDouble()

        if (isWeatherCacheValid) {
            readJsonWeather(JSONFILE)
        } else {
            lw.fetchCurrentWeatherByLocation(lat, lon)
        }
    }

    private fun handleCityNameWeather(lw: LocalWeather, cityName: String) {
        if (isWeatherCacheValid) {
            readJsonWeather(JSONFILE)
        } else {
            lw.fetchCurrentWeatherByCityName(cityName)
        }
    }

    private fun fetchGeoIPFallback(lw: LocalWeather) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geoJson = URL("http://api.sypexgeo.net").readText()
                if (geoJson.isNotEmpty()) {
                    val mJsonResponse = JSONObject(geoJson)
                    val mCityObj = mJsonResponse.getJSONObject("city")

                    when {
                        mCityObj.has("id") && !mCityObj.isNull("id") -> {
                            val mCode = mCityObj.getInt("id").toString()
                            if (isWeatherCacheValid) {
                                withContext(Dispatchers.Main) { readJsonWeather(JSONFILE) }
                            } else {
                                lw.fetchCurrentWeatherByCityId(mCode)
                            }
                        }

                        mCityObj.has("lat") && mCityObj.has("lon") -> {
                            val lat = mCityObj.getDouble("lat")
                            val lon = mCityObj.getDouble("lon")
                            if (isWeatherCacheValid) {
                                withContext(Dispatchers.Main) { readJsonWeather(JSONFILE) }
                            } else {
                                lw.fetchCurrentWeatherByLocation(lat, lon)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GeoIP fallback failed", e)
            }
        }
    }

    private fun setupWeatherCallbacks(lw: LocalWeather) {
        lw.weatherCallback = object : LocalWeather.WeatherCallback {
            override fun onSuccess(weather: Weather) {
                writeJsonWeather(weather)
                readJsonWeather(JSONFILE)
            }

            override fun onFailure(exception: Throwable?) {
                Log.e(TAG, "Weather fetching failed: ${exception?.message}")
                LauncherApp.toast("Weather error: ${exception?.message}", true)
            }
        }

        lw.fetchCurrentLocation(object : LocalWeather.CurrentLocationCallback {
            override fun onSuccess(location: Location) {
                if (isWeatherCacheValid) {
                    readJsonWeather(JSONFILE)
                } else {
                    lw.fetchCurrentWeatherByLocation(location)
                }
            }

            override fun onFailure(failed: LocationFailedEnum) {
                Log.e(TAG, "Location fetching failed: $failed")
                fetchGeoIPFallback(lw)
            }
        })
    }

    private val isWeatherCacheValid: Boolean
        get() = File(JSONFILE).let { it.exists() && it.lastModified() + maxCacheAge > System.currentTimeMillis() }


    private fun writeJsonWeather(weather: Weather) {
        try {
            // Log.d(TAG, "writeJsonWeather JSON: ${gson.toJson(weather)}")
            File(JSONFILE).writeText(gson.toJson(weather))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write weather cache", e)
        }
    }

    private fun readJsonWeather(filePath: String) {
        try {
            val cachedWeather = gson.fromJson(
                File(filePath).bufferedReader().use { it.readText() },
                Weather::class.java
            )
            cachedWeather?.let { updateWeatherDetails(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read weather cache", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateWeatherDetails(weather: Weather) {
        // Get views
        val weatherVG = findViewById<ViewGroup?>(R.id.weather)
        val detailsVG = findViewById<ViewGroup?>(R.id.details)
        val curLocTV = findViewById<TextView?>(R.id.curLocation)?.apply {
            setupMarquee()
        }
        // Set Location Info
        curLocTV?.text = weather.name.ifEmpty { "" }
        // Set Weather Info
        weatherVG?.let { group ->
            findViewById<AppCompatImageView>(R.id.weather_icon)?.let { icon ->
                OpenWeatherIcons(this, weather.icons[0], icon)
                icon.visibility = View.VISIBLE
            }

            findViewById<TextView>(R.id.curTemp)?.text =
                "${weather.temperature.toInt()}${getTempUnit()}"

            group.visibility = View.GONE
        }
        // Set Weather details
        detailsVG?.let { details ->
            findViewById<TextView>(R.id.hilotemp)?.text = getHiLoTempText(weather)
            findViewById<TextView>(R.id.pressure)?.text = getPressureText(weather)
            findViewById<TextView>(R.id.humidity)?.text =
                getString(R.string.weather_humidity, weather.humidity.toInt())
            findViewById<TextView>(R.id.wind)?.text = getWindText(weather)
            findViewById<TextView>(R.id.wDescription)?.apply {
                text = weather.descriptions[0]
                setupMarquee()
            }

            details.visibility = View.GONE
        }
        // Show
        if (RowPreferences.showLocation(this)) {
            showLocation(weatherVG, detailsVG, curLocTV)
        } else {
            showWeather(weatherVG, detailsVG)
        }
    }

    private fun TextView.setupMarquee() {
        ellipsize = TextUtils.TruncateAt.MARQUEE
        isSingleLine = true
        marqueeRepeatLimit = -1
        isSelected = true
        isFocusableInTouchMode = false
        isFocusable = false
    }

    private fun getTempUnit(): String = if (localWeather?.unit == Units.METRIC) "℃" else "℉"

    private fun getHiLoTempText(weather: Weather): String {
        return getString(
            R.string.weather_hilotemp,
            format(Locale.getDefault(), "%.0f", weather.minTemperature),
            format(Locale.getDefault(), "%.0f", weather.maxTemperature),
            getTempUnit()
        )
    }

    private fun getPressureText(weather: Weather): String {
        return if (localWeather?.lang == Lang.RUSSIAN) {
            getString(
                R.string.weather_pressure,
                format(Locale.getDefault(), "%.0f", weather.pressure / 1.333),
                getString(R.string.weather_pressure_mm)
            )
        } else {
            getString(
                R.string.weather_pressure,
                weather.pressure.toInt().toString(),
                getString(R.string.weather_pressure_hp)
            )
        }
    }

    private fun getWindText(weather: Weather): String {
        val speedUnit = if (localWeather?.unit == Units.METRIC) {
            getString(R.string.weather_speed_m)
        } else {
            getString(R.string.weather_speed_i)
        }
        val windDir = getCardinalDirection(weather.windAngle)

        return getString(
            R.string.weather_wind,
            format(Locale.getDefault(), "%.1f", weather.windSpeed),
            speedUnit,
            windDir
        )
    }

    private fun getCardinalDirection(angle: Double): String {
        val directions = if (localWeather?.lang == Lang.RUSSIAN)
            listOf("C", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ", "C")
        else
            listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        return directions[(angle % 360 / 45).roundToInt()]
    }

    private fun showLocation(
        weatherView: ViewGroup?,
        detailsView: ViewGroup?,
        curLocView: TextView?
    ) {
        weatherView?.visibility = View.GONE
        curLocView?.run {
            cancelWeatherAnimations()
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(fadeInDur * 2)
                .withEndAction {
                    animate()
                        .alpha(0f)
                        .setDuration(fadeOutDur)
                        .withEndAction {
                            visibility = View.GONE
                            showWeather(weatherView, detailsView)
                        }
                        .start()
                }
                .start()
        }
    }

    private fun showWeather(weatherView: ViewGroup?, detailsView: ViewGroup?) {
        cancelWeatherAnimations()

        // Initial state
        weatherView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
        }
        detailsView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
        }

        weatherAnimationJob = lifecycleScope.launch {
            while (isActive) {
                // 1. Fade in weather
                weatherView?.run {
                    animate()
                        .alpha(1f)
                        .setDuration(fadeInDur)
                        .withEndAction { /* no-op */ }
                        .start()
                }
                delay(fadeInDur)
                // 2. Show weather for 10 seconds
                delay(showCycleDur)
                // 3. Cross-fade to details
                weatherView?.run {
                    animate()
                        .alpha(0f)
                        .setDuration(fadeOutDur)
                        .start()
                }
                detailsView?.run {
                    animate()
                        .alpha(1f)
                        .setDuration(fadeInDur)
                        .start()
                }
                delay(maxOf(fadeOutDur, fadeInDur))
                // 4. Show details for 10 seconds
                delay(showCycleDur)
                // 5. Cross-fade back to weather
                detailsView?.run {
                    animate()
                        .alpha(0f)
                        .setDuration(fadeOutDur)
                        .start()
                }
                weatherView?.run {
                    animate()
                        .alpha(1f)
                        .setDuration(fadeInDur)
                        .start()
                }
                delay(maxOf(fadeOutDur, fadeInDur))
            }
        }
    }

    fun cancelWeatherAnimations() {
        weatherAnimationJob?.cancel()
        weatherAnimationJob = null

        // Immediately reset views to default state
        findViewById<ViewGroup?>(R.id.weather)?.apply {
            animate().cancel()
            alpha = 1f
        }
        findViewById<ViewGroup?>(R.id.details)?.apply {
            animate().cancel()
            alpha = 0f
        }
        findViewById<TextView>(R.id.curLocation)?.apply {
            animate().cancel()
            alpha = 0f
        }
    }

    private fun setShyMode(shy: Boolean, changeWallpaper: Boolean): Boolean {
        var changed = false
        if (mShyMode != shy) {
            mShyMode = shy
            changed = true
            if (mShyMode) {
                //if (BuildConfig.DEBUG) Log.d(TAG, "setShyMode(shy:$shy,changeWallpaper:$changeWallpaper) -> convertFromTranslucent() [mShyMode=$mShyMode]")
                convertFromTranslucent()
            } else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    //if (BuildConfig.DEBUG) Log.d(TAG, "setShyMode(shy:$shy,changeWallpaper:$changeWallpaper) convertToTranslucent() [mShyMode=$mShyMode]")
                    convertToTranslucent() // convertToTranslucent(null, null);
                }
            }
        }
        if (changeWallpaper && wallpaperView?.shynessMode != shy) {
            wallpaperView?.shynessMode = mShyMode
            if (mShyMode && mNotificationsView != null) {
                //if (BuildConfig.DEBUG) Log.d(TAG, "setShyMode(shy:$shy,changeWallpaper:$changeWallpaper) refreshSelectedBackground() [mShyMode=$mShyMode]")
                mNotificationsView?.refreshSelectedBackground()
            }
        }
        return changed
    }

    private fun convertFromTranslucent() {
        try {
            val convertFromTranslucent =
                Activity::class.java.getDeclaredMethod("convertFromTranslucent")
            convertFromTranslucent.isAccessible = true
            convertFromTranslucent.invoke(this@MainActivity)
        } catch (_: Throwable) {
        }
    }

    private fun convertToTranslucent() {
        try {
            var translucentConversionListenerClazz: Class<*>? = null
            for (clazz in Activity::class.java.declaredClasses) {
                if (clazz.simpleName.contains("TranslucentConversionListener")) {
                    translucentConversionListenerClazz = clazz
                }
            }
            val convertToTranslucent = Activity::class.java.getDeclaredMethod(
                "convertToTranslucent",
                translucentConversionListenerClazz,
                ActivityOptions::class.java
            )
            convertToTranslucent.isAccessible = true
            convertToTranslucent.invoke(this@MainActivity, null, null)
        } catch (_: Throwable) {
        }
    }

    private fun dismissLauncher(): Boolean {
        if (mShyMode) {
            return false
        }
        mLaunchAnimation.init(
            LauncherDismissAnimator(
                mListView,
                mFadeDismissAndSummonAnimations,
                homeAdapter!!.rowHeaders
            ), mMoveTaskToBack, 0.toByte()
        )
        mLaunchAnimation.start()
        return true
    }

    public override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        var exitingEditMode = false
        if (isInEditMode) {
            if (Util.isInTouchExploration(applicationContext)) {
                setTitle(R.string.app_label)
            }
            setEditMode(editMode = false, useAnimation = true)
            exitingEditMode = true
        }
        if (mLaunchAnimation.isRunning) {
            mLaunchAnimation.cancel()
            return
        }
        if (mLaunchAnimation.isPrimed) {
            mLaunchAnimation.reset()
        }
        if (mLaunchAnimation.isFinished) {
            mLaunchAnimation.reset()
        }
        if (!exitingEditMode) {
            intent?.extras?.let {
                if (it.getBoolean("extra_start_customize_apps")) {
                    startEditMode(3)
                } else if (it.getBoolean("extra_start_customize_games")) {
                    startEditMode(4)
                }
            }
            if (!mStartingEditMode) {
                if (!hasWindowFocus() || intent?.getBooleanExtra(
                        "com.android.systemui.recents.tv.RecentsTvActivity.RECENTS_HOME_INTENT_EXTRA",
                        false
                    ) == true
                ) {
                    if (!mLaunchAnimation.isScheduled) {
                        resetLauncherState(false)
                        mLaunchAnimation.init(
                            MassSlideAnimator.Builder(mListView)
                                .setDirection(MassSlideAnimator.Direction.SLIDE_IN)
                                .setFade(mFadeDismissAndSummonAnimations)
                                .build(), mRefreshHomeAdapter, 32.toByte()
                        )
                    }
                } else if (!dismissLauncher()) {
                    resetLauncherState(true)
                }
            }
        } else if (!mLaunchAnimation.isInitialized && !mLaunchAnimation.isScheduled) {
            resetLauncherState(false)
            mLaunchAnimation.init(
                MassSlideAnimator.Builder(mListView)
                    .setDirection(MassSlideAnimator.Direction.SLIDE_IN)
                    .setFade(mFadeDismissAndSummonAnimations)
                    .build(), mRefreshHomeAdapter, 32.toByte()
            )
        }
    }

    private fun startEditMode(rowType: Int) {
        if (Util.isInTouchExploration(applicationContext)) {
            setTitle(if (rowType == 3) R.string.title_app_edit_mode else R.string.title_game_edit_mode)
        }
        mStartingEditMode = true
        homeAdapter?.resetRowPositions(false)
        mLaunchAnimation.cancel()
        mListView?.selectedPosition = homeAdapter!!.getRowIndex(rowType)
        homeAdapter?.prepareEditMode(rowType)
    }

    private fun resetLauncherState(smooth: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "resetLauncherState(smooth:$smooth)")
        mScrollManager?.onScrolled(0, 0)
        mUserInteracted = false
        homeAdapter?.resetRowPositions(smooth)

        if (isInEditMode) {
            setEditMode(editMode = false, useAnimation = smooth)
        }
        val currIndex = mListView!!.selectedPosition
        var notifIndex = homeAdapter!!.getRowIndex(1) // 1 - Recommendations row
        mListView?.adapter?.let {
            notifIndex = (it.itemCount - 1).coerceAtMost(notifIndex)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "currIndex:$currIndex, notifIndex:$notifIndex")
        if (notifIndex != -1 && currIndex != notifIndex) {
            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "resetLauncherState -> set focus to Recommendations row"
            )
            if (smooth) {
                mListView?.setSelectedPositionSmooth(notifIndex)
            } else {
                mListView?.selectedPosition = notifIndex
                val focusedChild = mListView?.focusedChild
                focusedChild?.let { child ->
                    val focusedPosition = mListView?.getChildAdapterPosition(child)
                    if (focusedPosition == notifIndex) {
                        child.clearFocus()
                    }
                }
            }
            if (!(mShyMode || mNotificationsView == null)) {
                mNotificationsView?.setIgnoreNextActivateBackgroundChange()
            }
        } else if (notifIndex == -1) { // focus on 1st Apps cat (FAV|VIDEO|MUSIC|GAMES|APPS) in case No Notifications row
            val rowTypes = intArrayOf(
                7, // FAVORITES
                9, // VIDEO
                8, // MUSIC
                4, // GAMES
                3, // APPS
            ) // 0, 3, 4, 7, 8, 9 - SEARCH, APPS, GAMES, FAVORITES, MUSIC, VIDEO as in RowType()
            for (type in rowTypes) {
                var rowIndex = homeAdapter?.getRowIndex(type) ?: -1
                mListView?.adapter?.let {
                    rowIndex = (it.itemCount - 1).coerceAtMost(rowIndex)
                }
                if (rowIndex != -1) {
                    if (BuildConfig.DEBUG) Log.d(
                        TAG,
                        "resetLauncherState -> set focus to ${RowType.fromRowCode(type)} row"
                    )
                    if (smooth) {
                        mListView?.setSelectedPositionSmooth(rowIndex)
                    } else {
                        mListView?.selectedPosition = rowIndex
                    }
                    break
                }
            }
        }
        mLaunchAnimation.cancel()
    }

    private val isBackgroundVisibleBehind: Boolean
        get() {
            try {
                val isBackgroundVisibleBehind =
                    Activity::class.java.getDeclaredMethod("isBackgroundVisibleBehind")
                isBackgroundVisibleBehind.isAccessible = true
                return isBackgroundVisibleBehind.invoke(this@MainActivity) as Boolean
            } catch (_: Throwable) {
            }
            return false
        }

    override fun onStart() {
        super.onStart()
        mResetAfterIdleEnabled = false
        try {
            mAppWidgetHost?.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setShyMode(!isBackgroundVisibleBehind, true)

        wallpaperView?.resetBackground()
        homeAdapter?.refreshAdapterData()
        if (mKeepUiReset) {
            resetLauncherState(false)
        }

        if (!mStartingEditMode) {
            if (!mLaunchAnimation.isInitialized) {
                mLaunchAnimation.init(
                    LauncherReturnAnimator(
                        mListView,
                        mLaunchAnimation.lastKnownEpicenter,
                        homeAdapter!!.rowHeaders,
                        mHomeScreenView
                    ), mRefreshHomeAdapter, 32.toByte()
                )
            }
            mLaunchAnimation.schedule<LauncherReturnAnimator>()
        }
        mStartingEditMode = false
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {}

    override fun onResume() {
        var forceResort = true

        super.onResume()

        val shyChanged = setShyMode(!isBackgroundVisibleBehind, true)

        if (!getInstance(applicationContext)!!.checkIfResortingIsNeeded() || isInEditMode) {
            forceResort = false
        }
        homeAdapter?.sortRowsIfNeeded(forceResort)
        WallpaperInstaller.getInstance(this)?.installWallpaperIfNeeded()
        wallpaperView?.shynessMode = mShyMode
        if (shyChanged) {
            wallpaperView?.resetBackground()
        }
        if (mShyMode) {
            mNotificationsView?.refreshSelectedBackground()
        }
        if (!mHandler.hasMessages(6)) {
            mHandler.sendEmptyMessage(6)
        }
        homeAdapter?.animateSearchIn()
        for (i in mIdleListeners.indices) {
            mIdleListeners[i].onVisibilityChange(true)
        }
        mResetAfterIdleEnabled = true
        mHandler.removeMessages(3)
        mHandler.removeMessages(1)
        if (mIsIdle) {
            mHandler.sendEmptyMessage(2)
        } else {
            mHandler.sendEmptyMessageDelayed(1, mIdlePeriod.toLong())
        }
        mHandler.sendEmptyMessageDelayed(3, mResetPeriod.toLong())
        mHandler.sendEmptyMessageDelayed(7, 2000)

        if (mLaunchAnimation.isFinished) {
            mLaunchAnimation.reset()
        }
        if (mLaunchAnimation.isInitialized) {
            mLaunchAnimation.reset()
        }
        if (mLaunchAnimation.isScheduled) {
            primeAnimationAfterLayout()
        }
        mPauseAnimation.reset()

        if (isInEditMode) {
            if (mEditModeAnimation.isInitialized)
                mEditModeAnimation.reset()  // FIXME: added
            mEditModeAnimation.init(
                EditModeMassFadeAnimator(this, EditMode.ENTER),
                null,
                0.toByte()
            )
            mEditModeAnimation.start()
        }
        mUninstallRequested = false

        overridePendingTransition(R.anim.home_fade_in_top, R.anim.home_fade_out_bottom)

        if (!(homeAdapter == null || homeAdapter!!.isUiVisible || mDelayFirstRecommendationsVisible)) {
            homeAdapter?.onUiVisible()
        }
    }

    override fun onEnterAnimationComplete() {
        if (mLaunchAnimation.isScheduled || mLaunchAnimation.isPrimed) {
            mLaunchAnimation.start()
        }
    }

    override fun onPause() {
        super.onPause()
        mResetAfterIdleEnabled = false
        mLaunchAnimation.cancel()
        cancelWeatherAnimations()
        mHandler.removeMessages(1)
        mHandler.removeMessages(6)
        mHandler.removeMessages(7)
        for (i in mIdleListeners.indices) {
            mIdleListeners[i].onVisibilityChange(false)
        }
        if (isInEditMode) {
            if (mEditModeAnimation.isInitialized)
                mEditModeAnimation.reset() // FIXME: added
            mEditModeAnimation.init(
                EditModeMassFadeAnimator(this, EditMode.EXIT),
                null,
                0.toByte()
            )
            mEditModeAnimation.start()
        }
        mPauseAnimation.init(LauncherPauseAnimator(mListView), null, 0.toByte())
        mPauseAnimation.start()
        if (homeAdapter != null && homeAdapter!!.isUiVisible) {
            homeAdapter?.onUiInvisible()
            mDelayFirstRecommendationsVisible = false
        }
    }

    override fun onStop() {
        mResetAfterIdleEnabled = true
        try {
            mAppWidgetHost?.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mHandler.removeCallbacksAndMessages(null)
        mHandler.sendEmptyMessageDelayed(3, mResetPeriod.toLong())

        if (isInEditMode) {
            setEditMode(editMode = false, useAnimation = false)
        }
        setShyMode(shy = false, changeWallpaper = false)

        homeAdapter?.sortRowsIfNeeded(false)
        mLaunchAnimation.reset()
        super.onStop()
    }

    override fun dump(
        prefix: String,
        fd: FileDescriptor?,
        writer: PrintWriter,
        args: Array<String>?
    ) {
        super.dump(prefix, fd, writer, args)
        getInstance(applicationContext)?.dump(prefix, writer)
        mLaunchAnimation.dump(prefix, writer, mListView)
        homeAdapter?.dump(prefix, writer)
    }

    private val currentScrollPos: Int
        get() {
            var position = 0
            var topView = -1
            var rowIndex = 0
            while (rowIndex < mListView!!.childCount) {
                val child = mListView!!.getChildAt(rowIndex)
                if (child == null || child.top > 0) {
                    rowIndex++
                } else {
                    topView = mListView!!.getChildAdapterPosition(child)
                    if (child.measuredHeight > 0) {
                        position = (homeAdapter!!.getScrollOffset(topView)
                            .toFloat() * (abs(child.top).toFloat() / child.measuredHeight.toFloat()) * -1.0f).toInt()
                    }
                    topView--
                    while (topView >= 0) {
                        position -= homeAdapter!!.getScrollOffset(topView)
                        topView--
                    }
                    return position
                }
            }
            return 0 // position
        }

    private fun onNotificationRowStateUpdate(state: Int) {
        //if (BuildConfig.DEBUG) Log.d(TAG, "onNotificationRowStateUpdate(state: " + state + "), active row position: " + mList!!.selectedPosition)
        if (state == 1 || state == 2) {
            if (!mUserInteracted) {
                val searchIndex = homeAdapter!!.getRowIndex(0)
                if (searchIndex != -1) {
                    // focus on Search in case no recs yet
                    mListView?.selectedPosition = searchIndex
                    mListView?.getChildAt(searchIndex)?.requestFocus()
                    //if (BuildConfig.DEBUG) Log.d(TAG, "select search row and requestFocus()")
                }
            }
        } else if (state == 0 && mListView!!.selectedPosition <= homeAdapter!!.getRowIndex(1) && mNotificationsView!!.isNotEmpty()) {
            // focus on Recomendations
            mNotificationsView?.selectedPosition = 0
            mNotificationsView?.getChildAt(0)?.requestFocus()
            //if (BuildConfig.DEBUG) Log.d(TAG, "select recs row and focus on 1st")
        }
    }

    override fun onSearchRequested(): Boolean {
        setShyMode(shy = true, changeWallpaper = true)
        return super.onSearchRequested()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // ========== 语音按键处理（Remote X5 BLE 语音遥控器）==========
        // Remote X5 使用 Google GATT Voice Service 标准协议
        // Service UUID: 0000ffe0-0000-1000-8000-00805f9b34fb
        // Characteristic UUID: 0000ffe1-0000-1000-8000-00805f9b34fb
        // 音频编码: IMA-ADPCM (16kHz 单声道)
        // 
        // 关键：必须让系统处理按键以建立 BLE 音频通道！
        
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            Log.d(TAG, "onKeyDown: 检测到语音按键 keyCode=$keyCode")
            voiceKeyDownTime = System.currentTimeMillis()
            
            // 如果正在录音，停止录音
            if (isRecording) {
                Log.d(TAG, "onKeyDown: 已在录音中，停止录音")
                stopRecording()
                return true
            }
            
            // 按一下就开始录音
            // 让系统处理按键建立BLE通道，然后立即启动录音
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isRecording) {
                    Log.d(TAG, "onKeyDown: 启动录音（8秒自动停止）")
                    startMyVoiceAssistant()
                }
            }, 30) // 延迟30ms让系统建立BLE通道
            
            // 不拦截，让系统处理按键建立BLE音频通道
            return super.onKeyDown(keyCode, event)
        }

        return if (mLaunchAnimation.isPrimed || mLaunchAnimation.isRunning || mEditModeAnimation.isPrimed || mEditModeAnimation.isRunning) {
            when (keyCode) {
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK -> super.onKeyDown(keyCode, event)
                else -> true
            }
        } else if (mShyMode || !isMediaKey(event.keyCode)) {
            super.onKeyDown(keyCode, event)
        } else {
            true
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 语音按键释放
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            val pressDuration = System.currentTimeMillis() - voiceKeyDownTime
            Log.d(TAG, "onKeyUp: 语音按键释放，按下时长=${pressDuration}ms")
            
            // 长按超过2秒：启动BLE扫描（诊断功能）
            if (pressDuration >= LONG_PRESS_DURATION) {
                Log.d(TAG, "onKeyUp: 长按检测，启动BLE扫描")
                Toast.makeText(this, "正在扫描BLE设备...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    scanBleDevices()
                }
                return true
            }
            
            // 短按释放：不做任何操作
            // 录音会在8秒后自动停止
            return true
        }
        
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            val selectItem = mListView!!.focusedChild
            if (selectItem is ActiveFrame) {
                val v = selectItem.mRow
                val child = v?.focusedChild // TODO
                if (child is BannerView) {
                    val holder = child.viewHolder
                    if (holder != null) { // holder == null when the holder is an input
                        val pkg = holder.packageName
                        val intent = Intent(this, AppInfoActivity::class.java)
                        val bundle = Bundle()
                        bundle.putString("pkg", pkg)
                        intent.putExtras(bundle)
                        startActivity(intent)
                    }
                }
            }
            return true
        }
        return if (mShyMode || !isMediaKey(event.keyCode)) {
            super.onKeyUp(keyCode, event)
        } else when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                setShyMode(shy = true, changeWallpaper = true)
                true
            }

            else -> true
        }
    }

    // ==================== 语音助手功能 ====================

    /**
     * 启动语音助手
     * 1. 检查录音权限
     * 2. 开始录音
     * 3. 调用智谱API进行语音识别
     */
    private fun startMyVoiceAssistant() {
        Log.d(TAG, "startMyVoiceAssistant: 启动语音助手")

        // 检查是否正在录音
        if (isRecording) {
            Log.d(TAG, "startMyVoiceAssistant: 已在录音中，停止录音")
            stopRecording()
            return
        }

        // 检查录音权限
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            Log.d(TAG, "startMyVoiceAssistant: 请求录音权限")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSIONS_REQUEST_RECORD_AUDIO)
            return
        }

        // 检测音频输入设备
        logAudioInputDevices()
        
        // 检测已配对的蓝牙设备（诊断用）
        logBluetoothDevices()
        
        // 提示用户
        Toast.makeText(this, "请对着电视内置麦克风说话...", Toast.LENGTH_SHORT).show()
        
        // 直接开始录音（使用内置麦克风）
        // 注意：BLE HID 语音遥控器需要系统级支持才能路由音频到 AudioRecord
        // 当前设备 Remote X5 是 BLE HID 设备，不支持标准蓝牙音频(SCO)
        Log.d(TAG, "startMyVoiceAssistant: 使用内置麦克风录音（BLE语音遥控器需要系统级支持）")
        startRecording()
    }
    
    /**
     * 检测已配对的蓝牙设备
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun logBluetoothDevices() {
        Log.d(TAG, "========== 蓝牙设备检测 ==========")
        
        try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                Log.d(TAG, "设备不支持蓝牙")
                return
            }
            
            if (!bluetoothAdapter.isEnabled) {
                Log.d(TAG, "蓝牙未开启")
                return
            }
            
            val pairedDevices = bluetoothAdapter.bondedDevices
            Log.d(TAG, "已配对蓝牙设备: ${pairedDevices.size} 个")
            
            pairedDevices.forEachIndexed { index, device ->
                val deviceName = device.name ?: "未命名"
                val deviceAddress = device.address
                val deviceType = when (device.type) {
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "低功耗蓝牙(BLE)"
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
                    else -> "未知类型(${device.type})"
                }
                
                // 检查设备支持的 UUID（服务）
                val uuids = device.uuids?.map { it.toString() } ?: emptyList()
                val hasAudio = uuids.any { 
                    it.contains("Audio", ignoreCase = true) || 
                    it.contains("A2DP", ignoreCase = true) ||
                    it.contains("HFP", ignoreCase = true) ||
                    it.contains("HSP", ignoreCase = true) ||
                    it.contains("SCO", ignoreCase = true)
                }
                val hasHid = uuids.any { it.contains("HID", ignoreCase = true) }
                val hasVoice = uuids.any { 
                    it.contains("Voice", ignoreCase = true) ||
                    it.contains("1812", ignoreCase = true) // HID over GATT
                }
                
                Log.d(TAG, "  [$index] $deviceName ($deviceType)")
                Log.d(TAG, "       地址: $deviceAddress")
                Log.d(TAG, "       支持音频协议: $hasAudio")
                Log.d(TAG, "       支持HID: $hasHid")
                Log.d(TAG, "       支持语音: $hasVoice")
                
                // 打印所有 UUID（帮助诊断）
                if (uuids.isNotEmpty()) {
                    Log.d(TAG, "       所有UUID:")
                    uuids.forEachIndexed { i, uuid ->
                        Log.d(TAG, "         [$i] $uuid")
                    }
                }
            }
            
            // 检查蓝牙音频状态
            Log.d(TAG, "--- 蓝牙音频状态 ---")
            val am = getSystemService(android.media.AudioManager::class.java)
            Log.d(TAG, "SCO是否可用: ${am?.isBluetoothScoAvailableOffCall}")
            
        } catch (e: Exception) {
            Log.e(TAG, "logBluetoothDevices: 检测失败", e)
        }
        
        Log.d(TAG, "======================================")
    }

    /**
     * 扫描BLE设备并分析GATT服务
     * 用于诊断BLE HID语音遥控器的音频传输协议
     */
    private suspend fun scanBleDevices() {
        Log.d(TAG, "scanBleDevices: 开始扫描BLE设备")
        
        try {
            val bleScanner = com.amazon.tv.leanbacklauncher.ble.BleAudioScanner(this)
            
            // 检查蓝牙权限
            if (!bleScanner.hasBluetoothPermissions()) {
                Log.w(TAG, "scanBleDevices: 缺少蓝牙权限")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "缺少蓝牙权限", Toast.LENGTH_LONG).show()
                }
                return
            }
            
            // 获取已连接的BLE设备
            val devices = bleScanner.getConnectedBleDevices()
            Log.d(TAG, "scanBleDevices: 发现 ${devices.size} 个BLE设备")
            
            if (devices.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "未发现已配对的BLE设备", Toast.LENGTH_LONG).show()
                }
                return
            }
            
            // 扫描每个设备的服务
            for (device in devices) {
                Log.d(TAG, "scanBleDevices: 扫描设备 ${device.name} (${device.address})")
                
                val result = bleScanner.scanDeviceServices(device)
                
                result.onSuccess { discoveredDevice ->
                    val report = bleScanner.generateReport(discoveredDevice)
                    Log.d(TAG, report)
                    
                    // 查找 HID 服务中的音频特征
                    val hidService = discoveredDevice.services.find { 
                        it.uuid == "00001812-0000-1000-8000-00805f9b34fb" 
                    }
                    
                    val notifyChars = hidService?.characteristics?.filter { 
                        it.propertiesStr.contains("NOTIFY") 
                    } ?: emptyList()
                    
                    withContext(Dispatchers.Main) {
                        val message = buildString {
                            append("BLE扫描完成\n\n")
                            append("HID服务特征:\n")
                            notifyChars.forEach { char ->
                                append("• ${char.uuid.substring(0, 8)}...\n")
                                append("  ${char.propertiesStr}\n")
                            }
                            append("\n查看日志获取详细信息")
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                    
                    // 尝试读取 HID 特征值
                    if (hidService != null) {
                        Log.d(TAG, "========== 尝试读取 HID 特征值 ==========")
                        tryReadHidCharacteristics(device)
                    }
                }.onFailure { e ->
                    Log.e(TAG, "scanBleDevices: 扫描设备失败", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity, 
                            "扫描失败: ${e.message}", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanBleDevices: 扫描异常", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "扫描异常: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 尝试读取 HID 服务的特征值
     */
    private suspend fun tryReadHidCharacteristics(device: android.bluetooth.BluetoothDevice) {
        try {
            val bleReader = com.amazon.tv.leanbacklauncher.ble.BleAudioReader(this)
            val result = bleReader.connectAndFindAudio(device)
            
            result.onSuccess { message ->
                Log.d(TAG, "tryReadHidCharacteristics: $message")
            }.onFailure { e ->
                Log.e(TAG, "tryReadHidCharacteristics: 失败", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "tryReadHidCharacteristics: 异常", e)
        }
    }

    /**
     * 检测并打印音频输入设备信息
     */
    private fun logAudioInputDevices() {
        Log.d(TAG, "========== 音频输入设备检测 ==========")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioManager = getSystemService(android.media.AudioManager::class.java)
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                
                Log.d(TAG, "检测到 ${devices.size} 个音频输入设备:")
                devices.forEachIndexed { index, device ->
                    val typeStr = when (device.type) {
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO音频"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
                        android.media.AudioDeviceInfo.TYPE_DOCK -> "底座"
                        android.media.AudioDeviceInfo.TYPE_FM_TUNER -> "FM调谐器"
                        android.media.AudioDeviceInfo.TYPE_TV_TUNER -> "TV调谐器"
                        android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
                        android.media.AudioDeviceInfo.TYPE_AUX_LINE -> "AUX线路"
                        android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                        android.media.AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
                        3 -> "蓝牙音频(3)"  // 某些设备的蓝牙类型
                        7 -> "蓝牙音频(7)"  // 某些设备的蓝牙类型
                        else -> "类型${device.type}"
                    }
                    Log.d(TAG, "  [$index] $typeStr - ${device.productName}")
                }
                
                // 检查是否有蓝牙麦克风 (TYPE_BLUETOOTH_SCO=7, TYPE_BLUETOOTH_A2DP=8)
                val hasBluetoothMic = devices.any { 
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == 3 || it.type == 7 || it.type == 8  // 兼容不同设备
                }
                Log.d(TAG, "蓝牙音频设备: ${if (hasBluetoothMic) "已连接 ✓" else "未连接 ✗"}")
            } else {
                Log.d(TAG, "Android版本 < M，无法检测音频设备")
            }
        } catch (e: Exception) {
            Log.e(TAG, "logAudioInputDevices: 检测失败", e)
        }
        
        Log.d(TAG, "======================================")
    }

    /**
     * 功能1: 检测是否有可用的录音设备
     * @return true 有录音设备，false 无录音设备
     */
    private fun checkAudioInputDevice(): Boolean {
        Log.d(TAG, "========== 检测录音设备 ==========")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioManager = getSystemService(android.media.AudioManager::class.java)
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                
                if (devices.isEmpty()) {
                    Log.e(TAG, "checkAudioInputDevice: 未检测到任何音频输入设备!")
                    return false
                }
                
                Log.d(TAG, "checkAudioInputDevice: 检测到 ${devices.size} 个音频输入设备:")
                
                // 检查是否有有效的麦克风
                val validMicTypes = listOf(
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC,      // 内置麦克风
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,    // 蓝牙SCO
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,    // 有线耳机
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE        // USB设备
                )
                
                var hasValidMic = false
                devices.forEach { device ->
                    val typeStr = when (device.type) {
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风 ✓"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦克风 ✓"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机 ✓"
                        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备 ✓"
                        else -> "类型${device.type}"
                    }
                    Log.d(TAG, "  - $typeStr: ${device.productName}")
                    
                    if (device.type in validMicTypes) {
                        hasValidMic = true
                    }
                }
                
                if (hasValidMic) {
                    Log.d(TAG, "checkAudioInputDevice: 检测到有效麦克风 ✓")
                } else {
                    Log.e(TAG, "checkAudioInputDevice: 未检测到有效麦克风!")
                }
                
                Log.d(TAG, "======================================")
                return hasValidMic
                
            } else {
                // Android M 以下版本，假设有麦克风
                Log.d(TAG, "checkAudioInputDevice: Android < M，假设有麦克风")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAudioInputDevice: 检测失败", e)
            return false
        }
    }

    /**
     * 功能2: 清理旧的录音文件，防止缓存变大
     */
    private fun cleanOldRecordings() {
        try {
            val audioDir = File(getExternalFilesDir(null), "voice_recordings")
            
            if (!audioDir.exists() || !audioDir.isDirectory) {
                Log.d(TAG, "cleanOldRecordings: 录音目录不存在，无需清理")
                return
            }
            
            val files = audioDir.listFiles()
            if (files.isNullOrEmpty()) {
                Log.d(TAG, "cleanOldRecordings: 没有旧录音文件")
                return
            }
            
            var deletedCount = 0
            var totalSize = 0L
            
            files.forEach { file ->
                if (file.isFile && file.name.endsWith(".wav")) {
                    totalSize += file.length()
                    if (file.delete()) {
                        deletedCount++
                        Log.d(TAG, "cleanOldRecordings: 删除文件 ${file.name}")
                    } else {
                        Log.w(TAG, "cleanOldRecordings: 删除失败 ${file.name}")
                    }
                }
            }
            
            Log.d(TAG, "cleanOldRecordings: 清理完成，删除 $deletedCount 个文件，释放 ${totalSize / 1024}KB")
            
        } catch (e: Exception) {
            Log.e(TAG, "cleanOldRecordings: 清理失败", e)
        }
    }

    /**
     * 开始录音
     */
    private fun startRecording() {
        try {
            Log.d(TAG, "startRecording: 开始录音")

            // ========== 检测录音设备 ==========
            val hasMic = checkAudioInputDevice()
            if (!hasMic) {
                Log.e(TAG, "startRecording: 未检测到录音设备!")
                Toast.makeText(this, "未检测到录音设备，请检查麦克风连接", Toast.LENGTH_LONG).show()
                return
            }

            // ========== 功能2: 清理旧的录音文件 ==========
            cleanOldRecordings()

            // 创建录音文件 - 保存到外部存储目录（用户可访问）
            val audioDir = File(getExternalFilesDir(null), "voice_recordings")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
                Log.d(TAG, "startRecording: 创建目录 ${audioDir.absolutePath}")
            }
            
            val audioFile = File(audioDir, "voice_recording_${System.currentTimeMillis()}.wav")
            Log.d(TAG, "startRecording: 录音文件路径: ${audioFile.absolutePath}")

            // 初始化 AudioRecord
            // 尝试多种音频源，优先使用蓝牙麦克风
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            
            // 音频源优先级：Remote X5 使用 GATT Voice Service，系统自动路由
            val audioSources = listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",  // 蓝牙通话
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.DEFAULT to "DEFAULT"
            )
            
            var initialized = false
            for ((source, sourceName) in audioSources) {
                Log.d(TAG, "startRecording: 尝试音频源: $sourceName")
                audioRecord = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
                )
                
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "startRecording: 成功使用音频源: $sourceName")
                    initialized = true
                    break
                } else {
                    Log.w(TAG, "startRecording: 音频源 $sourceName 初始化失败，尝试下一个")
                    audioRecord?.release()
                    audioRecord = null
                }
            }
            
            if (!initialized) {
                Log.e(TAG, "startRecording: 所有音频源初始化失败")
                Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show()
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            // ========== 麦克风预热测试 ==========
            // 读取几帧数据测试麦克风是否真的工作
            Log.d(TAG, "startRecording: 麦克风预热测试...")
            val testBuffer = ByteArray(bufferSize)
            var testMaxAmp = 0
            for (i in 0 until 5) {  // 读取5帧测试
                val read = audioRecord?.read(testBuffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    for (j in 0 until read step 2) {
                        val sample = (testBuffer[j].toInt() and 0xFF) or (testBuffer[j + 1].toInt() shl 8)
                        val amp = kotlin.math.abs(sample)
                        if (amp > testMaxAmp) testMaxAmp = amp
                    }
                }
                Thread.sleep(50)
            }
            Log.d(TAG, "startRecording: 麦克风预热测试完成，最大振幅=$testMaxAmp")
            
            if (testMaxAmp == 0) {
                Log.w(TAG, "startRecording: 麦克风可能不可用！")
                Toast.makeText(
                    this, 
                    "警告：麦克风可能不可用\n可能原因：\n1. 电视棒无物理麦克风\n2. 电视麦克风无法传递给电视棒\n建议：使用USB麦克风", 
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "正在录音（8秒）...", Toast.LENGTH_SHORT).show()
            }

            // 使用协程进行录音
            recordingJob = lifecycleScope.launch(Dispatchers.IO) {
                val outputStream = FileOutputStream(audioFile)
                val buffer = ByteArray(bufferSize)

                // 写入 WAV 文件头
                writeWavHeader(outputStream, SAMPLE_RATE, 1, 16, 0)

                var totalBytes = 0L
                var maxAmplitude = 0
                var silentFrameCount = 0
                var totalFrameCount = 0
                val startTime = System.currentTimeMillis()
                var lastVolumeLogTime = startTime
                var warningShown = false  // 是否已显示过警告

                try {
                    while (isRecording && (System.currentTimeMillis() - startTime) < MAX_RECORD_DURATION) {
                        val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            totalFrameCount++
                            
                            // ========== 实时音量检测 ==========
                            // 计算当前帧的最大振幅
                            var frameMaxAmp = 0
                            for (i in 0 until bytesRead step 2) {
                                // 16bit PCM，小端序
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val amplitude = kotlin.math.abs(sample)
                                if (amplitude > frameMaxAmp) {
                                    frameMaxAmp = amplitude
                                }
                            }
                            
                            if (frameMaxAmp > maxAmplitude) {
                                maxAmplitude = frameMaxAmp
                            }
                            
                            // 静音检测（振幅 < 100 认为是静音）
                            if (frameMaxAmp < 100) {
                                silentFrameCount++
                            }
                            
                            // 每500ms输出一次音量信息
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastVolumeLogTime >= 500) {
                                val volumePercent = (frameMaxAmp * 100 / 32767).coerceAtMost(100)
                                val volumeBar = buildString {
                                    repeat(20) { i ->
                                        append(if (i < volumePercent / 5) "█" else "░")
                                    }
                                }
                                Log.d(TAG, "音量: $volumeBar $volumePercent% (振幅=$frameMaxAmp)")
                                lastVolumeLogTime = currentTime
                                
                                // 如果前3秒一直是静音，提前警告用户
                                if (!warningShown && maxAmplitude == 0 && (currentTime - startTime) > 3000) {
                                    warningShown = true
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "未检测到音频输入！\n可能原因：\n1. 电视棒无物理麦克风\n2. 电视麦克风无法传递给电视棒\n建议：使用USB麦克风",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startRecording: 录音错误", e)
                } finally {
                    outputStream.close()
                }

                // 更新 WAV 文件头
                updateWavHeader(audioFile, totalBytes)

                // 计算静音比例
                val silentRatio = if (totalFrameCount > 0) {
                    silentFrameCount * 100.0 / totalFrameCount
                } else {
                    100.0
                }

                withContext(Dispatchers.Main) {
                    stopRecording()
                    
                    // 输出录音质量报告
                    Log.d(TAG, "========== 录音质量报告 ==========")
                    Log.d(TAG, "录音时长: ${(System.currentTimeMillis() - startTime) / 1000} 秒")
                    Log.d(TAG, "最大振幅: $maxAmplitude / 32767")
                    Log.d(TAG, "静音帧比例: ${String.format("%.1f", silentRatio)}%")
                    Log.d(TAG, "文件大小: ${audioFile.length()} bytes")
                    Log.d(TAG, "==================================")
                    
                    // 判断是否有有效音频
                    val hasValidAudio = maxAmplitude > 500 && silentRatio < 90
                    
                    if (!hasValidAudio) {
                        Log.w(TAG, "startRecording: 录音可能是静音！")
                        Toast.makeText(
                            this@MainActivity, 
                            "录音可能没有声音，请检查麦克风或说话音量", 
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    Log.d(TAG, "startRecording: 录音完成，文件大小=${audioFile.length()} bytes")

                    // 验证WAV文件格式
                    if (verifyWavFile(audioFile)) {
                        Log.d(TAG, "startRecording: WAV文件格式正确")
                        sendToSpeechAPI(audioFile)
                    } else {
                        Log.e(TAG, "startRecording: WAV文件格式错误!")
                        Toast.makeText(this@MainActivity, "WAV文件格式错误", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "startRecording: 录音启动失败", e)
            Toast.makeText(this, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    /**
     * 停止录音
     */
    private fun stopRecording() {
        Log.d(TAG, "stopRecording: 停止录音")
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: 停止录音错误", e)
        }

        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * 写入 WAV 文件头
     */
    private fun writeWavHeader(outputStream: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int, dataLength: Long) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // 不使用 .use{}，因为流需要在录音过程中保持打开
        // RIFF header
        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(36 + dataLength, 4)) // File length
        outputStream.write("WAVE".toByteArray())

        // fmt chunk
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16, 4)) // Subchunk1Size
        outputStream.write(intToByteArray(1, 2))  // AudioFormat (PCM)
        outputStream.write(intToByteArray(channels.toLong(), 2)) // NumChannels
        outputStream.write(intToByteArray(sampleRate.toLong(), 4)) // SampleRate
        outputStream.write(intToByteArray(byteRate.toLong(), 4))   // ByteRate
        outputStream.write(intToByteArray(blockAlign.toLong(), 2)) // BlockAlign
        outputStream.write(intToByteArray(bitsPerSample.toLong(), 2)) // BitsPerSample

        // data chunk
        outputStream.write("data".toByteArray())
        outputStream.write(intToByteArray(dataLength, 4)) // Subchunk2Size
    }

    /**
     * 更新 WAV 文件头中的数据长度（小端序）
     */
    private fun updateWavHeader(file: File, dataLength: Long) {
        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                // 更新文件总长度 (位置4，4字节，小端序)
                raf.seek(4)
                val totalSize = (36 + dataLength).toInt()
                raf.write(totalSize and 0xFF)
                raf.write((totalSize shr 8) and 0xFF)
                raf.write((totalSize shr 16) and 0xFF)
                raf.write((totalSize shr 24) and 0xFF)
                
                // 更新数据长度 (位置40，4字节，小端序)
                raf.seek(40)
                val dataSize = dataLength.toInt()
                raf.write(dataSize and 0xFF)
                raf.write((dataSize shr 8) and 0xFF)
                raf.write((dataSize shr 16) and 0xFF)
                raf.write((dataSize shr 24) and 0xFF)
            }
            Log.d(TAG, "updateWavHeader: 更新成功，数据长度=$dataLength bytes")
        } catch (e: Exception) {
            Log.e(TAG, "updateWavHeader: 更新WAV头错误", e)
        }
    }

    /**
     * Int 转 ByteArray
     */
    private fun intToByteArray(value: Long, size: Int): ByteArray {
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return result
    }

    /**
     * 验证 WAV 文件格式是否正确
     */
    private fun verifyWavFile(file: File): Boolean {
        try {
            val inputStream = java.io.DataInputStream(java.io.FileInputStream(file))
            val header = ByteArray(44)
            inputStream.read(header)
            inputStream.close()

            // 检查 RIFF 标识
            val riff = String(header, 0, 4, Charsets.US_ASCII)
            if (riff != "RIFF") {
                Log.e(TAG, "verifyWavFile: 无效的RIFF标识: $riff")
                return false
            }

            // 检查 WAVE 标识
            val wave = String(header, 8, 4, Charsets.US_ASCII)
            if (wave != "WAVE") {
                Log.e(TAG, "verifyWavFile: 无效的WAVE标识: $wave")
                return false
            }

            // 检查 fmt 标识
            val fmt = String(header, 12, 4, Charsets.US_ASCII)
            if (fmt != "fmt ") {
                Log.e(TAG, "verifyWavFile: 无效的fmt标识: $fmt")
                return false
            }

            // 读取音频格式参数
            val audioFormat = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8)
            val numChannels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
            val sampleRate = byteArrayToInt(header, 24, 4)
            val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)

            Log.d(TAG, "verifyWavFile: WAV文件信息:")
            Log.d(TAG, "  - 格式: $audioFormat (1=PCM)")
            Log.d(TAG, "  - 声道数: $numChannels")
            Log.d(TAG, "  - 采样率: $sampleRate Hz")
            Log.d(TAG, "  - 位深: $bitsPerSample bits")
            Log.d(TAG, "  - 文件大小: ${file.length()} bytes")

            // 验证参数是否符合语音识别要求
            if (audioFormat != 1) {
                Log.e(TAG, "verifyWavFile: 音频格式错误，应为PCM(1)，实际为 $audioFormat")
                return false
            }
            if (numChannels != 1) {
                Log.e(TAG, "verifyWavFile: 声道数错误，应为单声道(1)，实际为 $numChannels")
                return false
            }
            if (sampleRate != SAMPLE_RATE) {
                Log.e(TAG, "verifyWavFile: 采样率错误，应为 $SAMPLE_RATE，实际为 $sampleRate")
                return false
            }
            if (bitsPerSample != 16) {
                Log.e(TAG, "verifyWavFile: 位深错误，应为16，实际为 $bitsPerSample")
                return false
            }

            Log.d(TAG, "verifyWavFile: WAV格式验证通过 ✓")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "verifyWavFile: 验证失败", e)
            return false
        }
    }

    /**
     * ByteArray 转 Int (小端序)
     */
    private fun byteArrayToInt(bytes: ByteArray, offset: Int, length: Int): Int {
        var result = 0
        for (i in 0 until length) {
            result = result or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return result
    }

    /**
     * 调用百度语音识别API
     * 流程：1. 获取access_token  2. 调用语音识别接口
     */
    private fun sendToSpeechAPI(audioFile: File) {
        Log.d(TAG, "sendToSpeechAPI: 开始调用百度语音识别API")
        Toast.makeText(this, "正在识别语音...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 验证并打印音频文件详细信息
                Log.d(TAG, "========== 音频文件信息 ==========")
                Log.d(TAG, "文件名: ${audioFile.name}")
                Log.d(TAG, "文件大小: ${audioFile.length()} bytes")
                Log.d(TAG, "采样率: $SAMPLE_RATE Hz")
                Log.d(TAG, "声道: 单声道")
                Log.d(TAG, "位深: 16bit")
                
                // 检查音频数据是否有效（非静音）
                val audioBytes = audioFile.readBytes()
                val headerSize = 44
                if (audioBytes.size <= headerSize) {
                    Log.e(TAG, "sendToSpeechAPI: 音频数据为空!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "音频数据为空", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // 统计非零字节数量
                val audioData = audioBytes.copyOfRange(headerSize, audioBytes.size)
                var nonZeroCount = 0
                var maxAmplitude = 0
                for (i in audioData.indices) {
                    if (audioData[i] != 0.toByte()) nonZeroCount++
                    val amplitude = Math.abs(audioData[i].toInt())
                    if (amplitude > maxAmplitude) maxAmplitude = amplitude
                }
                val nonZeroPercent = (nonZeroCount * 100.0 / audioData.size)
                Log.d(TAG, "音频数据分析:")
                Log.d(TAG, "  - 音频数据大小: ${audioData.size} bytes")
                Log.d(TAG, "  - 非零字节: $nonZeroCount (${String.format("%.1f", nonZeroPercent)}%)")
                Log.d(TAG, "  - 最大振幅: $maxAmplitude")
                Log.d(TAG, "================================")
                
                if (nonZeroPercent < 5.0) {
                    Log.w(TAG, "sendToSpeechAPI: 警告 - 音频可能是静音!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "录音可能是静音，请说话后重试", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Step 1: 获取百度 access_token
                val tokenUrl = "$BAIDU_TOKEN_URL?grant_type=client_credentials&client_id=$BAIDU_API_KEY&client_secret=$BAIDU_SECRET_KEY"
                Log.d(TAG, "sendToSpeechAPI: 正在获取access_token...")

                val tokenRequest = Request.Builder()
                    .url(tokenUrl)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()

                val tokenResponse = okHttpClient.newCall(tokenRequest).execute()
                val tokenBody = tokenResponse.body?.string()
                Log.d(TAG, "sendToSpeechAPI: Token响应=$tokenBody")

                if (!tokenResponse.isSuccessful || tokenBody == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "获取Token失败: ${tokenResponse.code}", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 解析 access_token
                val tokenJson = JsonParser.parseString(tokenBody).asJsonObject
                val accessToken = tokenJson.get("access_token")?.asString

                if (accessToken == null) {
                    val errorMsg = tokenJson.get("error_description")?.asString ?: "未知错误"
                    Log.e(TAG, "sendToSpeechAPI: 获取Token失败 - $errorMsg")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Token错误: $errorMsg", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                Log.d(TAG, "sendToSpeechAPI: 获取Token成功")

                // Step 2: 调用语音识别接口
                // 百度要求：音频数据需要base64编码（复用前面已读取的audioBytes）
                val audioBase64 = java.util.Base64.getEncoder().encodeToString(audioBytes)

                val jsonBody = """
                    {
                        "format": "wav",
                        "rate": $SAMPLE_RATE,
                        "channel": 1,
                        "cuid": "android_tv",
                        "token": "$accessToken",
                        "speech": "$audioBase64",
                        "len": ${audioBytes.size}
                    }
                """.trimIndent()

                Log.d(TAG, "sendToSpeechAPI: 正在识别语音...")
                Log.d(TAG, "sendToSpeechAPI: 音频数据大小=${audioBytes.size} bytes")

                val asrRequest = Request.Builder()
                    .url(BAIDU_ASR_URL)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val asrResponse = okHttpClient.newCall(asrRequest).execute()
                val asrBody = asrResponse.body?.string()
                Log.d(TAG, "sendToSpeechAPI: HTTP状态码=${asrResponse.code}")
                Log.d(TAG, "sendToSpeechAPI: 响应体=$asrBody")

                withContext(Dispatchers.Main) {
                    if (asrResponse.isSuccessful && asrBody != null) {
                        try {
                            val jsonObject = JsonParser.parseString(asrBody).asJsonObject
                            val errNo = jsonObject.get("err_no")?.asInt ?: -1
                            val errMsg = jsonObject.get("err_msg")?.asString ?: ""

                            if (errNo == 0) {
                                // 成功
                                val resultArray = jsonObject.get("result")?.asJsonArray
                                val text = resultArray?.get(0)?.asString ?: "未识别到文本"

                                Log.d(TAG, "sendToSpeechAPI: 识别成功!")
                                Toast.makeText(this@MainActivity, "识别结果: $text", Toast.LENGTH_LONG).show()

                                Log.d(TAG, "========== 语音识别结果 ==========")
                                Log.d(TAG, "识别文本: $text")
                                Log.d(TAG, "================================")

                                // 根据识别文本匹配意图并打开对应APP
                                handleVoiceIntent(text)
                            } else {
                                Log.e(TAG, "sendToSpeechAPI: 识别失败 err_no=$errNo, err_msg=$errMsg")
                                Toast.makeText(this@MainActivity, "识别失败: $errMsg", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "sendToSpeechAPI: JSON解析错误", e)
                            Toast.makeText(this@MainActivity, "解析响应失败", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e(TAG, "sendToSpeechAPI: API调用失败 code=${asrResponse.code}")
                        Toast.makeText(this@MainActivity, "API调用失败: ${asrResponse.code}", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "sendToSpeechAPI: 调用API异常", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "API调用异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 根据语音识别文本匹配意图并打开对应APP
     */
    private fun handleVoiceIntent(text: String) {
        Log.d(TAG, "handleVoiceIntent: 开始匹配意图, 文本=$text")
        
        // 定义意图规则
        val intentRules = listOf(
            VoiceIntent(
                intent = "账户余额",
                packageName = "com.bank.app1",
                keywords = listOf("账户", "余额", "查询", "账户余额", "我的账户", "个人账号", "主账户", "子账户", "账户明细", "账户概览", "账户情况", "余额查询", "可用余额", "实时余额", "剩余金额", "卡里钱", "钱包余额", "存款余额", "查余额", "查账户", "看看余额", "查下账户", "查一下余额", "账户查询", "银行卡")
            ),
            VoiceIntent(
                intent = "转账",
                packageName = "com.bank.app2",
                keywords = listOf("转账", "转账汇款", "转钱", "打款", "汇款", "跨行转账", "同行转账", "转账给他人", "转款", "手机转账", "网银转账", "快速转账", "实时转账", "定时转账", "转账到银行卡", "转笔钱", "汇钱", "打钱过去", "转一下", "转个账")
            ),
            VoiceIntent(
                intent = "缴费",
                packageName = "com.bank.app3",
                keywords = listOf("缴费", "水费", "话费", "电费", "燃气费", "有线电视", "生活缴费", "缴费用", "交费", "缴费充值", "在线缴费", "自助缴费", "交水费", "水费缴纳", "自来水费", "水费查询", "交电费", "电费缴纳", "电费查询", "电表缴费", "交燃气费", "燃气费缴纳", "煤气费", "燃气缴费", "充话费", "交话费", "话费充值", "手机缴费", "流量充值", "有线电视费", "广电缴费", "数字电视费", "电视缴费", "物业费", "宽带费", "取暖费", "停车费", "社保缴费", "医保缴费")
            ),
            VoiceIntent(
                intent = "交易明细",
                packageName = "com.bank.app4",
                keywords = listOf("账单", "明细", "账单查询", "我的账单", "月度账单", "年度账单", "电子账单", "对账单", "账单明细", "交易明细", "收支明细", "消费明细", "账户明细", "流水明细", "交易记录", "收支记录", "消费记录", "查流水", "看账单", "看明细", "查消费记录", "看交易记录")
            ),
            VoiceIntent(
                intent = "理财",
                packageName = "com.bank.app5",
                keywords = listOf("理财", "基金", "股票", "养老金", "投资理财", "个人理财", "财富管理", "理财规划", "稳健理财", "活期理财", "定期理财", "基金理财", "买基金", "基金定投", "指数基金", "货币基金", "债券基金", "基金赎回", "股票交易", "炒股", "买股票", "股票持仓", "A股", "港股", "美股", "股票行情", "养老金理财", "养老投资", "个人养老金", "养老基金", "养老理财", "债券", "保险理财", "贵金属", "外汇", "信托", "私募", "定投", "理财收益", "资产配置")
            ),
            VoiceIntent(
                intent = "电视",
                packageName = "com.xiaodianshi.tv.yst",
                keywords = listOf(
                    "电视","电影", "视频", "打开电视", "开启电视", "电视节目", "看B站", "打开B站", "哔哩哔哩", "看bilibili", "看抖音", "看视频", "刷抖音", "看快手", "打开快手", "刷快手", "打开视频", "音乐"
                )
            )
        )
        
        // 匹配意图
        var matchedIntent: VoiceIntent? = null
        var matchedKeyword: String? = null
        
        for (rule in intentRules) {
            for (keyword in rule.keywords) {
                if (text.contains(keyword)) {
                    matchedIntent = rule
                    matchedKeyword = keyword
                    break
                }
            }
            if (matchedIntent != null) break
        }
        
        if (matchedIntent != null) {
            Log.d(TAG, "handleVoiceIntent: 匹配成功! 意图=${matchedIntent.intent}, 关键词=$matchedKeyword, 包名=${matchedIntent.packageName}")
            //Toast.makeText(this, "正在打开${matchedIntent.intent}...", Toast.LENGTH_SHORT).show()
            openAppByPackage(matchedIntent.packageName, matchedIntent.intent)
        } else {
            Log.d(TAG, "handleVoiceIntent: 未匹配到意图")
            Toast.makeText(this, "未识别的指令: $text", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 意图规则数据类
     */
    private data class VoiceIntent(
        val intent: String,
        val packageName: String,
        val keywords: List<String>
    )
    
    /**
     * 根据包名打开APP
     */
    private fun openAppByPackage(packageName: String, intentName: String) {
        try {
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                Log.d(TAG, "openAppByPackage: 成功打开 $intentName ($packageName)")
            } else {
                Log.e(TAG, "openAppByPackage: 未找到应用 $packageName")
                Toast.makeText(this, "未安装${intentName}应用", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "openAppByPackage: 打开应用失败", e)
            Toast.makeText(this, "打开应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 语音助手功能结束 ====================

    private fun addWidget(refresh: Boolean) {
        val wrapper: ViewGroup? = findViewById<View>(R.id.widget_wrapper) as? LinearLayout?
        wrapper?.let { wrap ->
            if (refresh || mAppWidgetHostView == null) {
                wrap.removeAllViews()
                var success = false
                var appWidgetId = Util.getWidgetId(this)
                val appWidgetComp = Partner.get(this).widgetComponentName
                if (appWidgetComp != null) {
                    for (appWidgetInfo in mAppWidgetManager!!.installedProviders) {
                        if (appWidgetComp == appWidgetInfo.provider) {
                            success = appWidgetId != 0
                            if (success && appWidgetComp != Util.getWidgetComponentName(this)) {
                                clearWidget(appWidgetId)
                                success = false
                            }
                            if (!success) {
                                val width = resources.getDimension(R.dimen.widget_width).toInt()
                                val height =
                                    resources.getDimension(R.dimen.widget_height).toInt()
                                val options = Bundle()
                                options.putInt("appWidgetMinWidth", width)
                                options.putInt("appWidgetMaxWidth", width)
                                options.putInt("appWidgetMinHeight", height)
                                options.putInt("appWidgetMaxHeight", height)
                                appWidgetId = mAppWidgetHost?.allocateAppWidgetId() ?: 0
                                success = mAppWidgetManager?.bindAppWidgetIdIfAllowed(
                                    appWidgetId,
                                    appWidgetInfo.provider,
                                    options
                                ) == true
                            }
                            if (success) {
                                mAppWidgetHostView =
                                    mAppWidgetHost?.createView(this, appWidgetId, appWidgetInfo)
                                mAppWidgetHostView?.setAppWidget(appWidgetId, appWidgetInfo)
                                wrap.addView(mAppWidgetHostView)
                                Util.setWidget(this, appWidgetId, appWidgetInfo.provider)
                            }
                        }
                    }
                }
                if (!success) {
                    clearWidget(appWidgetId)
                    // clock
                    wrap.addView(LayoutInflater.from(this).inflate(R.layout.clock, wrap, false))
                    val typeface = ResourcesCompat.getFont(this, R.font.sfuidisplay_thin)
                    val clockView: TextView? = findViewById<View>(R.id.clock) as ClockView?
                    typeface?.let {
                        clockView?.typeface = typeface
                    }
                    // settings
                    val settingsVG: ViewGroup? =
                        findViewById<View>(R.id.settings) as LinearLayout?
                    settingsVG?.let { group ->
                        val sel = findViewById<ImageView>(R.id.settings_selection_circle)
                        sel?.setColorFilter(
                            RowPreferences.getFrameColor(this),
                            PorterDuff.Mode.SRC_ATOP
                        )
                        val icon = findViewById<ImageView>(R.id.settings_icon)
                        group.setOnClickListener {
                            startSettings()
                        }
                        group.setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                sel?.alpha = 1.0f
                                icon?.clearAnimation()
                                icon?.alpha = 1.0f
                            } else {
                                //sel?.alpha = 0.0f
                                sel?.animate()?.apply {
                                    interpolator = LinearInterpolator()
                                    duration = 500
                                    alpha(0.0f)
                                    start()
                                }
                                icon?.alpha = 1.0f
                                icon?.breath()
                            }
                        }
                        sel?.alpha = 0.0f
                        icon?.alpha = 0.0f
                        icon?.animate()?.apply {
                            interpolator = LinearInterpolator()
                            duration = 500
                            alpha(1.0f)
                            start()
                        }
                        icon?.breath()
                    }
                    // weather widget update
                    initializeWeather()
                    return
                }
                return
            }
            val parent = mAppWidgetHostView!!.parent as ViewGroup
            if (parent !== wrap) {
                parent.removeView(mAppWidgetHostView)
                wrap.removeAllViews()
                wrap.addView(mAppWidgetHostView)
            }
        }
    }

    private fun startSettings() {
        if (applicationContext.resources.getBoolean(R.bool.full_screen_settings_enabled)) {
            val intent = Intent(this@MainActivity, LegacyHomeScreenSettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        } else if (applicationContext.resources.getBoolean(R.bool.side_panel_settings_enabled)) {
            val intent = Intent(this@MainActivity, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
    }

    private fun clearWidget(appWidgetId: Int) {
        if (appWidgetId != 0) {
            mAppWidgetHost?.deleteAppWidgetId(appWidgetId)
        }
        Util.clearWidget(this)
    }

    val editModeWallpaper: View
        get() = findViewById(R.id.edit_mode_background)

    private fun setEditMode(editMode: Boolean, useAnimation: Boolean) {
        var alpha = 1.0f
        mEditModeAnimation.reset()
        if (useAnimation) {
            mEditModeAnimation.init(
                EditModeMassFadeAnimator(
                    this,
                    if (editMode) EditMode.ENTER else EditMode.EXIT
                ), null, 0.toByte()
            )
            mEditModeAnimation.start()
        } else {
            val launcherWallpaper = wallpaperView
            val f: Float = if (editMode) {
                0.0f
            } else {
                1.0f
            }
            launcherWallpaper?.alpha = f
            val editModeWallpaper = editModeWallpaper
            if (!editMode) {
                alpha = 0.0f
            }
            editModeWallpaper.alpha = alpha
            editModeWallpaper.visibility = View.VISIBLE
            homeAdapter?.setRowAlphas(if (editMode) 0 else 1)
        }
        // FIXME: wrong focus and useless with no DIM
        if (!editMode && isInEditMode) {
            for (i in 0 until mListView!!.childCount) {
                val activeFrame = mListView?.getChildAt(i)
                if (activeFrame is ActiveFrame) {
                    for (j in 0 until activeFrame.childCount) {
                        val activeItemsRow = activeFrame.getChildAt(j)
                        if (activeItemsRow is EditableAppsRowView) {
                            activeItemsRow.editMode = false
                        }
                    }
                }
            }
        }
        isInEditMode = editMode
    }

    @SuppressLint("WrongConstant")
    private fun checkFirstRunAfterBoot(): Boolean {
        val dummyIntent = Intent("android.intent.category.LEANBACK_LAUNCHER")
        dummyIntent.setClass(this, DummyActivity::class.java)
        val firstRun = PendingIntent.getActivity(this, 0, dummyIntent, FLAG_NO_CREATE) == null
        if (firstRun) {
            (getSystemService(ALARM_SERVICE) as AlarmManager)[AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 864000000000L] =
                PendingIntent.getActivity(this, 0, dummyIntent, 0)
        }
        return firstRun
    }

    fun beginLaunchAnimation(
        view: View,
        translucent: Boolean,
        color: Int,
        onCompleteCallback: Runnable
    ) {
        if (!mLaunchAnimation.isPrimed && !mLaunchAnimation.isRunning && !mLaunchAnimation.isFinished) {
            getBoundsOnScreen(view, mLaunchAnimation.lastKnownEpicenter)
            if (translucent) {
                onCompleteCallback.run()
                return
            }
            val animation: ForwardingAnimatorSet = if (view is NotificationCardView) {
                NotificationLaunchAnimator(
                    mListView,
                    view,
                    mLaunchAnimation.lastKnownEpicenter,
                    findViewById<View>(R.id.click_circle_layer) as ImageView,
                    color,
                    homeAdapter!!.rowHeaders,
                    mHomeScreenView
                )
            } else {
                LauncherLaunchAnimator(
                    mListView,
                    view,
                    mLaunchAnimation.lastKnownEpicenter,
                    findViewById<View>(R.id.click_circle_layer) as ImageView,
                    color,
                    homeAdapter!!.rowHeaders,
                    mHomeScreenView
                )
            }
            mLaunchAnimation.init(animation, onCompleteCallback, 0.toByte())
            mLaunchAnimation.start()
        }
    }

    val isLaunchAnimationInProgress: Boolean
        get() = mLaunchAnimation.isPrimed || mLaunchAnimation.isRunning

    val isEditAnimationInProgress: Boolean
        get() = mEditModeAnimation.isPrimed || mEditModeAnimation.isRunning

    fun includeInLaunchAnimation(target: View?) {
        mLaunchAnimation.include(target)
    }

    fun includeInEditAnimation(target: View?) {
        mEditModeAnimation.include(target)
    }

    fun excludeFromLaunchAnimation(target: View?) {
        mLaunchAnimation.exclude(target)
    }

    fun excludeFromEditAnimation(target: View?) {
        mEditModeAnimation.exclude(target)
    }

    fun setOnLaunchAnimationFinishedListener(l: OnAnimationFinishedListener?) {
        mLaunchAnimation.setOnAnimationFinishedListener(l)
    }

    private fun primeAnimationAfterLayout() {
        mListView?.rootView?.viewTreeObserver?.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                mListView?.rootView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                if (mLaunchAnimation.isScheduled) {
                    mLaunchAnimation.prime()
                }
            }
        })
        mListView?.requestLayout()
    }

    private fun checkLaunchPointPositions() {
        if (!mLaunchAnimation.isRunning && checkViewHierarchy(mListView)) {
//            val buf = StringWriter()
//            buf.append("Caught partially animated state; resetting...\n")
//            mLaunchAnimation.dump("", PrintWriter(buf), mList)
//            Log.w(TAG, "Animations:$buf")
            mLaunchAnimation.reset()
        }
    }

    private fun checkViewHierarchy(view: View?): Boolean {
        if (view is ParticipatesInLaunchAnimation && view.translationY != 0.0f) {
            return true
        }
        if (view is ViewGroup) {
            val n = view.childCount
            for (i in 0 until n) {
                if (checkViewHierarchy(view.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * Root 权限工具类
 */
object RootUtil {

    /**
     * 判断 App 是否拥有 Root 权限
     * true = 已授权
     * false = 未授权 / 被拒绝 / 无法提权
     */
    fun hasRootPermission(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            output.contains("uid=0") || output.contains("root")
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * 判断设备是否 Root (不弹授权框)
     */
    fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su"
        )
        return paths.any { java.io.File(it).exists() }
    }
}

