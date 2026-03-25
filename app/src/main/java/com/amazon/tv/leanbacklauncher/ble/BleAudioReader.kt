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
 * 尝试从 HID 语音遥控器读取音频数据
 */
class BleAudioReader(private val context: Context) {
    
    companion object {
        private const val TAG = "BleAudioReader"
        
        // 标准服务 UUID
        val HID_SERVICE_UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        
        // HID 服务中的特征 UUID
        val HID_REPORT_UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        val HID_REPORT_MAP_UUID = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        val HID_BOOT_KEYBOARD_UUID = UUID.fromString("00002a22-0000-1000-8000-00805f9b34fb")
        val HID_BOOT_MOUSE_UUID = UUID.fromString("00002a33-0000-1000-8000-00805f9b34fb")
        
        // 可能的音频数据格式
        const val AUDIO_FORMAT_ADPCM = 1
        const val AUDIO_FORMAT_PCM = 2
        const val AUDIO_FORMAT_OPUS = 3
    }
    
    private var bluetoothGatt: BluetoothGatt? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null
    private var isListening = false
    private var audioDataCallback: ((ByteArray) -> Unit)? = null
    
    /**
     * 连接到设备并查找音频特征
     */
    suspend fun connectAndFindAudio(device: BluetoothDevice): Result<String> = withContext(Dispatchers.IO) {
        if (!hasBluetoothPermissions()) {
            return@withContext Result.failure(SecurityException("缺少蓝牙权限"))
        }
        
        try {
            Log.d(TAG, "连接到设备: ${device.name} (${device.address})")
            
            val connectionResult = CompletableResult<String>()
            
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT 已连接，开始发现服务")
                        gatt?.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT 已断开")
                        connectionResult.complete(Result.success("断开连接"))
                    }
                }
                
                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                        Log.d(TAG, "发现 ${gatt.services.size} 个服务")
                        
                        // 查找 HID 服务
                        val hidService = gatt.getService(HID_SERVICE_UUID)
                        if (hidService != null) {
                            Log.d(TAG, "找到 HID 服务，特征数量: ${hidService.characteristics.size}")
                            
                            // 列出所有特征
                            hidService.characteristics.forEach { char ->
                                Log.d(TAG, "  特征: ${char.uuid}")
                                Log.d(TAG, "    属性: ${formatProperties(char.properties)}")
                                
                                // 查找支持 NOTIFY 的特征（可能是音频）
                                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                    Log.d(TAG, "    ⭐ 支持 NOTIFY，可能是音频特征")
                                    
                                    // 尝试读取特征值
                                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                                        gatt.readCharacteristic(char)
                                    }
                                }
                            }
                            
                            connectionResult.complete(Result.success("找到 HID 服务，${hidService.characteristics.size} 个特征"))
                        } else {
                            Log.w(TAG, "未找到 HID 服务")
                            connectionResult.complete(Result.failure(Exception("未找到 HID 服务")))
                        }
                    }
                }
                
                override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                        val value = characteristic.value
                        Log.d(TAG, "读取特征 ${characteristic.uuid}:")
                        Log.d(TAG, "  数据长度: ${value.size} bytes")
                        Log.d(TAG, "  数据内容: ${bytesToHex(value)}")
                        
                        // 分析数据格式
                        analyzeAudioData(value)
                    }
                }
                
                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    if (characteristic != null) {
                        val value = characteristic.value
                        Log.d(TAG, "特征变化 ${characteristic.uuid}: ${value.size} bytes")
                        
                        // 回调音频数据
                        audioDataCallback?.invoke(value)
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
     * 启动音频监听
     */
    fun startAudioListening(callback: (ByteArray) -> Unit): Boolean {
        if (bluetoothGatt == null) {
            Log.e(TAG, "未连接设备")
            return false
        }
        
        val hidService = bluetoothGatt?.getService(HID_SERVICE_UUID) ?: return false
        
        // 查找支持 NOTIFY 的特征
        for (char in hidService.characteristics) {
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                Log.d(TAG, "启用通知: ${char.uuid}")
                
                // 启用通知
                bluetoothGatt?.setCharacteristicNotification(char, true)
                
                // 写入 CCCD (Client Characteristic Configuration Descriptor)
                val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGatt?.writeDescriptor(descriptor)
                
                audioCharacteristic = char
                audioDataCallback = callback
                isListening = true
                
                return true
            }
        }
        
        return false
    }
    
    /**
     * 停止音频监听
     */
    fun stopAudioListening() {
        if (audioCharacteristic != null && bluetoothGatt != null) {
            bluetoothGatt?.setCharacteristicNotification(audioCharacteristic, false)
            
            val descriptor = audioCharacteristic?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            bluetoothGatt?.writeDescriptor(descriptor)
        }
        
        isListening = false
        audioDataCallback = null
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        stopAudioListening()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
    
    /**
     * 分析音频数据格式
     */
    private fun analyzeAudioData(data: ByteArray) {
        if (data.isEmpty()) {
            Log.d(TAG, "数据为空")
            return
        }
        
        Log.d(TAG, "========== 音频数据分析 ==========")
        
        // 检查是否像 ADPCM 数据
        val uniqueBytes = data.toSet().size
        val zeroCount = data.count { it == 0.toByte() }
        
        Log.d(TAG, "数据长度: ${data.size}")
        Log.d(TAG, "唯一字节数: $uniqueBytes")
        Log.d(TAG, "零字节比例: ${zeroCount * 100 / data.size}%")
        
        // 打印前 32 字节的十六进制
        Log.d(TAG, "前32字节: ${bytesToHex(data.take(32).toByteArray())}")
        
        // 猜测数据格式
        if (data.size > 100 && uniqueBytes > 50) {
            Log.d(TAG, "可能是音频数据（有足够的变化）")
        } else if (zeroCount > data.size * 80 / 100) {
            Log.d(TAG, "可能是静音数据（大部分为零）")
        } else {
            Log.d(TAG, "可能是控制数据或压缩音频")
        }
        
        Log.d(TAG, "==================================")
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
