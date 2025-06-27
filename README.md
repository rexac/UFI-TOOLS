<div style="display:flex;justify-content:center">
<img src="https://kanokano.cn/wp-content/uploads/2025/04/5acb8625d65a3fd5d7b228830a9450a1.webp" style="width:50%;text-align:center" />
</div>

## What Features Does It Have?

* **Remote management (requires LAN penetration)**
* **Send and receive SMS messages**
* **SMS forwarding**
* **Send AT commands**
* **Local network speed testing**
* **Customizable themes and backgrounds**
* **Real-time display of various parameters**
  (QCI speed, CPU temperature, memory load, signal strength, SNR, PCI, cell ID, frequency band, IPv6 address, etc.)
* **Lock frequency band and cell without reboot**
* **USB debugging and auto-start for network USB debugging**
* **Dual-end support** (can be installed and used on a phone or installed as a server on an F50 device)
* **Auto-start on boot**
* **One-click OTA updates**
* **Performance mode, LED indicator, and file sharing toggles**
* **3G/4G/5G network switching**
* **More features coming soon!**

![](img/5.png) 

| ![](img/1.jpg) | ![](img/2.jpg) |
| -------------- | -------------- |

| ![](img/3.jpg) | ![](img/4.jpg) |
| -------------- | -------------- |

---

## How to Use

**For Android Users:**

1. Download and install the APK on your phone, then open it.
2. Make sure your phone is connected to the same network as the portable WiFi device. Open the control webpage, log in, and enable ADB functionality.
3. Use ADB on your computer or phone to connect to the portable WiFi and install the APK on the device.
4. Use remote control software like scrcpy to launch zte-ufi-tools, configure the gateway, start the service, disable battery optimization, and enable notifications (to ensure it can auto-start on boot).
5. Open your phone’s browser, visit the portable WiFi’s IP address on port 2333, and start using the tool.

**For iOS Users:**

> iOS users need to enable ADB the traditional way: connect to WiFi, then open
> `http://192.168.0.1/index.html#usb_port` and turn on ADB.
> From step 3 onwards, follow the Android user instructions.

**Notes:**

* Functionality depends on your device model and firmware version. The version I tested with perfect results is **MU300\_ZYV1.0.0B09**.
* CPU usage, temperature, and memory usage data come from your phone if you install the APK on it, since there are no official APIs for these on the portable WiFi device itself.

**Download link:**
[https://www.123684.com/s/7oa5Vv-dQLD3](https://www.123684.com/s/7oa5Vv-dQLD3? pwd:CkS) pwd:CkSj

**API Documentation(Chinese):**
[https://kanokano.cn/wp-content/uploads/2025/06/UFI-TOOLSAPI文档.html](https://kanokano.cn/wp-content/uploads/2025/06/UFI-TOOLSAPI文档.html)

---

If you want, I can help you polish it further or add a more technical section!
