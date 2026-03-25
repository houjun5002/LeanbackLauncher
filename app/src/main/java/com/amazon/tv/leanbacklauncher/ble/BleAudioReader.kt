package com.amazon.tv.leanbacklauncher.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * BLE 音频读取器
 * 尝试从 BLE 语音遥控器读取音频数据
 * 
 * 支持 Remote X5 遥控器：
 * - Google GATT Voice Service 标准协议
 * - Service UUID: 0000ffe0-0000-1000-8000-00805f9b34fb
 * - Characteristic UUID: 0000ffe1-0000-1000-8000-00805f9b34fb
 * - 音频编码: IMA-ADPCM (16kHz 单声道)
 */
class BleAudioReader(private val context: Context) {
    
    companion object {
        private const val TAG = "BleAudioReader"
        
        // ========== Google GATT Voice Service (Remote X5 使用) ==========
        val VOICE_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val VOICE_DATA_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        
        // 标准服务 UUID
        val HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        
        // HID 服务中的特征 UUID
        val HID_REPORT_UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        val HID_REPORT_MAP_UUID = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        val HID_BOOT_KEYBOARD_UUID = UUID.fromString("00002a22-0000-1000-8000-00805f9b34fb")
        val HID_BOOT_MOUSE_UUID = UUID.fromString("00002a33-0000-1000-8000-00805f9b34fb")
        
        // CCCD UUID (用于启用通知)
        val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        // 音频参数
        const val AUDIO_SAMPLE_RATE = 16000
        const val AUDIO_CHANNELS = 1
        const val AUDIO_BITS = 16
        
        // 可能的音频数据格式
        const val AUDIO_FORMAT_ADPCM = 1
        const val AUDIO_FORMAT_PCM = 2
        const val AUDIO_FORMAT_OPUS = 3
    }
    
    private var bluetoothGatt: BluetoothGatt? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null
    private var isListening = false
    private var audioDataCallback: ((ByteArray) -> Unit)? = null
    private var totalAudioBytes = 0L
    
    /**
     * 连接到设备并启动语音监听
     * 尝试连接 Google GATT Voice Service
     */
    suspend fun connectAndFindAudio(device: BluetoothDevice): Result<String> = withContext(Dispatchers.IO) {
        if (!hasBluetoothPermissions()) {
            return@withContext Result.failure(SecurityException("缺少蓝牙权限"))
        }
        
        try {
            Log.d(TAG, "========== 连接 BLE 设备 ==========")
            Log.d(TAG, "设备: ${device.name} (${device.address})")
            
            val connectionResult = CompletableResult<String>()
            
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "GATT 已连接，开始发现服务")
                            gatt?.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "GATT 已断开")
                            connectionResult.complete(Result.success("断开连接"))
                        }
                    }
                }
                
                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                        connectionResult.complete(Result.failure(Exception("服务发现失败")))
                        return
                    }
                    
                    Log.d(TAG, "发现 ${gatt.services.size} 个服务")
                    
                    // 1. 首先查找 Google GATT Voice Service
                    var voiceService = gatt.getService(VOICE_SERVICE_UUID)
                    
                    if (voiceService != null) {
                        Log.d(TAG, "✓ 找到 Google GATT Voice Service (0000ffe0)")
                        
                        val voiceChar = voiceService.getCharacteristic(VOICE_DATA_UUID)
                        if (voiceChar != null) {
                            Log.d(TAG, "✓ 找到语音数据特征 (0000ffe1)")
                            Log.d(TAG, "  属性: ${formatProperties(voiceChar.properties)}")
                            
                            audioCharacteristic = voiceChar
                            connectionResult.complete(Result.success("找到 GATT Voice Service"))
                            return
                        }
                    }
                    
                    // 2. 如果没找到，尝试查找 HID 服务
                    val hidService = gatt.getService(HID_SERVICE_UUID)
                    if (hidService != null) {
                        Log.d(TAG, "找到 HID 服务 (00001812)")
                        Log.d(TAG, "  特征数量: ${hidService.characteristics.size}")
                        
                        // 查找支持 NOTIFY 的特征
                        for (char in hidService.characteristics) {
                            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                Log.d(TAG, "  支持 NOTIFY: ${char.uuid}")
                            }
                        }
                        
                        connectionResult.complete(Result.success("找到 HID 服务"))
                        return
                    }
                    
                    // 3. 列出所有服务（帮助诊断）
                    Log.d(TAG, "所有服务:")
                    gatt.services.forEach { service ->
                        Log.d(TAG, "  ${service.uuid}")
                    }
                    
                    connectionResult.complete(Result.failure(Exception("未找到语音服务")))
                }
                
                override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                        val value = characteristic.value
                        Log.d(TAG, "读取特征 ${characteristic.uuid}:")
                        Log.d(TAG, "  数据长度: ${value.size} bytes")
                        Log.d(TAG, "  数据内容: ${bytesToHex(value.take(32).toByteArray())}")
                    }
                }
                
                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    if (characteristic != null) {
                        val value = characteristic.value
                        totalAudioBytes += value.size
                        
                        // 输出接收到的数据
                        Log.d(TAG, "收到音频数据: ${value.size} bytes (累计: ${totalAudioBytes} bytes)")
                        
                        // 回调音频数据
                        audioDataCallback?.invoke(value)
                    }
                }
                
                override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "✓ 通知已启用，开始接收音频数据")
                        Log.d(TAG, "请对着遥控器说话...")
                    } else {
                        Log.e(TAG, "启用通知失败: status=$status")
                    }
                }
            }
            
            // 连接 GATT
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            
            // 等待结果
            return@withContext connectionResult.await()
            
        } catch (e: Exception) {
            Log.e(TAG, "连接失败", e)
            return@withContext Result.failure(e)
        }
    }
    
    /**
     * 启动语音监听（直接从 BLE 读取音频）
     * 需要：用户按住遥控器语音键
     */
    fun startVoiceListening(callback: (ByteArray) -> Unit): Boolean {
        if (bluetoothGatt == null) {
            Log.e(TAG, "未连接设备")
            return false
        }
        
        // 查找语音特征
        val voiceService = bluetoothGatt?.getService(VOICE_SERVICE_UUID)
        val voiceChar = voiceService?.getCharacteristic(VOICE_DATA_UUID)
        
        if (voiceChar == null) {
            Log.e(TAG, "未找到语音特征")
            return false
        }
        
        Log.d(TAG, "========== 启动语音监听 ==========")
        Log.d(TAG, "特征: ${voiceChar.uuid}")
        
        // 启用通知
        val enabled = bluetoothGatt?.setCharacteristicNotification(voiceChar, true) ?: false
        Log.d(TAG, "设置通知: $enabled")
        
        // 写入 CCCD 启用通知
        val descriptor = voiceChar.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            bluetoothGatt?.writeDescriptor(descriptor)
        } else {
            Log.e(TAG, "未找到 CCCD 描述符")
            return false
        }
        
        audioCharacteristic = voiceChar
        audioDataCallback = callback
        isListening = true
        totalAudioBytes = 0L
        
        return true
    }
    
    /**
     * 停止语音监听
     */
    fun stopVoiceListening() {
        if (audioCharacteristic != null && bluetoothGatt != null) {
            bluetoothGatt?.setCharacteristicNotification(audioCharacteristic, false)
            
            val descriptor = audioCharacteristic?.getDescriptor(CCCD_UUID)
            descriptor?.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            bluetoothGatt?.writeDescriptor(descriptor)
        }
        
        Log.d(TAG, "停止语音监听，共接收 ${totalAudioBytes} bytes")
        isListening = false
        audioDataCallback = null
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        stopVoiceListening()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
    
    /**
     * 检查蓝牙权限
     */
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    /**
     * 格式化属性
     */
    private fun formatProperties(properties: Int): String {
        val props = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WRITE_NO_RESP")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")
        return props.joinToString(", ")
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
    
    /**
     * 可完成的 Result（用于协程等待回调）
     */
    private class CompletableResult<T> {
        private var result: Result<T>? = null
        private val lock = Object()
        
        fun complete(result: Result<T>) {
            synchronized(lock) {
                this.result = result
                lock.notifyAll()
            }
        }
        
        suspend fun await(): Result<T> {
            synchronized(lock) {
                while (result == null) {
                    lock.wait()
                }
                return result!!
            }
        }
    }
}
