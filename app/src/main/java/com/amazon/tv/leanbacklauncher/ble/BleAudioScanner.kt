package com.amazon.tv.leanbacklauncher.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * BLE 音频扫描工具
 * 用于分析 BLE HID 语音遥控器的 GATT 服务结构
 */
class BleAudioScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "BleAudioScanner"
        
        // 常见的音频相关 UUID
        val KNOWN_AUDIO_UUIDS = mapOf(
            // 标准音频服务
            "0000180a-0000-1000-8000-00805f9b34fb" to "Device Information Service",
            "0000180d-0000-1000-8000-00805f9b34fb" to "Heart Rate Service",
            "00001812-0000-1000-8000-00805f9b34fb" to "HID Service",
            
            // 可能的音频 Characteristic
            "00002b29-0000-1000-8000-00805f9b34fb" to "Audio Output",
            "00002b2a-0000-1000-8000-00805f9b34fb" to "Audio Input",
            
            // VOICE_REMOCON 常见 UUID（不同厂商可能不同）
            "0000180f-0000-1000-8000-00805f9b34fb" to "Battery Service",
            
            // 常见语音遥控器厂商 UUID 前缀
            // Amazon Fire TV Remote
            "a9339137-2134-4c3c-aeaf-36978191be01" to "Amazon Voice Service",
            // Xiaomi
            "00001530-1212-efde-1523-785feabcd123" to "Xiaomi Voice",
            // 其他可能的
            "9fbf120d-6301-11e4-9ab5-0002a5d5c51b" to "Possible Voice Char",
        )
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    
    // 存储发现的设备和服务
    data class DiscoveredDevice(
        val device: BluetoothDevice,
        val name: String,
        val address: String,
        val bondState: Int,
        val type: Int,
        val services: List<DiscoveredService>
    )
    
    data class DiscoveredService(
        val uuid: String,
        val name: String,
        val characteristics: List<DiscoveredCharacteristic>
    )
    
    data class DiscoveredCharacteristic(
        val uuid: String,
        val name: String,
        val properties: Int,
        val propertiesStr: String,
        val isAudioRelated: Boolean
    )
    
    /**
     * 检查蓝牙权限
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 获取已连接的 BLE 设备列表
     */
    fun getConnectedBleDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Missing Bluetooth permissions")
            return emptyList()
        }
        
        val devices = mutableListOf<BluetoothDevice>()
        
        // 获取所有已配对的设备
        val bondedDevices = bluetoothAdapter.bondedDevices
        Log.d(TAG, "已配对设备数量: ${bondedDevices.size}")
        
        for (device in bondedDevices) {
            Log.d(TAG, "配对设备: ${device.name} (${device.address}), type=${device.type}")
            // BLE 设备 type 为 2 (LE) 或 3 (DUAL)
            if (device.type == BluetoothDevice.DEVICE_TYPE_LE || 
                device.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                devices.add(device)
            }
        }
        
        // 检查连接状态
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        Log.d(TAG, "GATT 连接设备数量: ${connectedDevices.size}")
        
        return devices
    }
    
    /**
     * 扫描设备的 GATT 服务
     */
    suspend fun scanDeviceServices(device: BluetoothDevice): Result<DiscoveredDevice> = withContext(Dispatchers.IO) {
        if (!hasBluetoothPermissions()) {
            return@withContext Result.failure(SecurityException("Missing Bluetooth permissions"))
        }
        
        val services = mutableListOf<DiscoveredService>()
        var gatt: BluetoothGatt? = null
        
        try {
            Log.d(TAG, "开始扫描设备: ${device.name} (${device.address})")
            
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "连接状态变化: status=$status, newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT 已连接，开始发现服务")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT 已断开")
                    }
                }
                
                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                        Log.d(TAG, "发现 ${gatt.services.size} 个服务")
                        
                        for (service in gatt.services) {
                            val serviceInfo = parseService(service)
                            services.add(serviceInfo)
                            Log.d(TAG, "服务: ${serviceInfo.uuid} - ${serviceInfo.name}")
                            
                            for (char in serviceInfo.characteristics) {
                                Log.d(TAG, "  特征: ${char.uuid} - ${char.name}")
                                Log.d(TAG, "    属性: ${char.propertiesStr}")
                                if (char.isAudioRelated) {
                                    Log.w(TAG, "  ⚠️ 可能是音频相关特征!")
                                }
                            }
                        }
                        
                        // 扫描完成，断开连接
                        gatt.disconnect()
                    } else {
                        Log.e(TAG, "服务发现失败: status=$status")
                    }
                }
            }
            
            // 连接 GATT
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            
            // 等待扫描完成
            var waitCount = 0
            while (services.isEmpty() && waitCount < 50) { // 最多等待 5 秒
                Thread.sleep(100)
                waitCount++
            }
            
            val discoveredDevice = DiscoveredDevice(
                device = device,
                name = device.name ?: "Unknown",
                address = device.address,
                bondState = device.bondState,
                type = device.type,
                services = services
            )
            
            Result.success(discoveredDevice)
        } catch (e: Exception) {
            Log.e(TAG, "扫描失败", e)
            Result.failure(e)
        } finally {
            gatt?.close()
        }
    }
    
    /**
     * 解析服务
     */
    private fun parseService(service: BluetoothGattService): DiscoveredService {
        val uuid = service.uuid.toString().lowercase()
        val name = KNOWN_AUDIO_UUIDS[uuid] ?: "Unknown Service"
        
        val characteristics = service.characteristics.map { char ->
            parseCharacteristic(char)
        }
        
        return DiscoveredService(
            uuid = uuid,
            name = name,
            characteristics = characteristics
        )
    }
    
    /**
     * 解析特征
     */
    private fun parseCharacteristic(char: BluetoothGattCharacteristic): DiscoveredCharacteristic {
        val uuid = char.uuid.toString().lowercase()
        val name = KNOWN_AUDIO_UUIDS[uuid] ?: "Unknown Characteristic"
        val properties = char.properties
        
        // 解析属性
        val props = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WRITE_NO_RESP")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) props.add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) props.add("EXTENDED")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) props.add("SIGNED_WRITE")
        
        // 判断是否可能是音频相关
        val isAudioRelated = isAudioCharacteristic(uuid, properties)
        
        return DiscoveredCharacteristic(
            uuid = uuid,
            name = name,
            properties = properties,
            propertiesStr = props.joinToString(", "),
            isAudioRelated = isAudioRelated
        )
    }
    
    /**
     * 判断是否可能是音频特征
     */
    private fun isAudioCharacteristic(uuid: String, properties: Int): Boolean {
        // 音频特征通常有 NOTIFY 属性（用于实时数据传输）
        val hasNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val hasRead = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        
        // 检查 UUID 是否包含 audio/voice 相关关键词
        val uuidLower = uuid.lowercase()
        val knownAudio = KNOWN_AUDIO_UUIDS.keys.any { 
            uuidLower == it.lowercase() && 
            (KNOWN_AUDIO_UUIDS[it]?.contains("Audio", ignoreCase = true) == true ||
             KNOWN_AUDIO_UUIDS[it]?.contains("Voice", ignoreCase = true) == true)
        }
        
        // 检查是否是 HID 服务下的可能音频特征
        // HID 语音遥控器通常在 HID 服务下有额外的音频特征
        return knownAudio || (hasNotify && hasRead)
    }
    
    /**
     * 生成扫描报告
     */
    fun generateReport(device: DiscoveredDevice): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("BLE 设备扫描报告")
        sb.appendLine("========================================")
        sb.appendLine("设备名称: ${device.name}")
        sb.appendLine("设备地址: ${device.address}")
        sb.appendLine("设备类型: ${when(device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
            else -> "未知 (${device.type})"
        }}")
        sb.appendLine("配对状态: ${when(device.bondState) {
            BluetoothDevice.BOND_NONE -> "未配对"
            BluetoothDevice.BOND_BONDING -> "配对中"
            BluetoothDevice.BOND_BONDED -> "已配对"
            else -> "未知"
        }}")
        sb.appendLine()
        sb.appendLine("发现的服务: ${device.services.size} 个")
        sb.appendLine("----------------------------------------")
        
        for (service in device.services) {
            sb.appendLine()
            sb.appendLine("服务 UUID: ${service.uuid}")
            sb.appendLine("服务名称: ${service.name}")
            sb.appendLine("特征数量: ${service.characteristics.size}")
            
            for (char in service.characteristics) {
                sb.appendLine("  ├── 特征: ${char.uuid}")
                sb.appendLine("  │   名称: ${char.name}")
                sb.appendLine("  │   属性: ${char.propertiesStr}")
                if (char.isAudioRelated) {
                    sb.appendLine("  │   ⚠️ 可能是音频特征!")
                }
            }
        }
        
        sb.appendLine()
        sb.appendLine("========================================")
        
        return sb.toString()
    }
}
