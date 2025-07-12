<div style="display:flex;justify-content:center">
<img src="https://kanokano.cn/wp-content/uploads/2025/04/5acb8625d65a3fd5d7b228830a9450a1.webp" style="width:50%;text-align:center" />
</div>

## What features does it have?
 
* **Remote management (requires intranet penetration)**
* **SMS sending and receiving**
* **SMS forwarding**
* **AT command sending**
* **Intranet speed test**
* **Theme + background customization**
* **Real-time display of various parameters (QCI rate, CPU temperature, memory load, signal strength, SNR, PCI, cell number, frequency band, IPv6 address, etc.)**
* **Lock frequency band, lock cell (no restart required)**
* **USB debugging, network USB debugging auto-start**
* **Available on both ends (can be installed and used on mobile phones (none), or installed as a server for F50)**
* **Auto-start on boot**
* **One-click OTA**
* **Performance mode, indicator light, file sharing switch**
* **3G/4G/5G network switching**
* **Other functions will continue to be updated in the future**

|   ![](img/1.jpg)   |   ![](img/2.jpg)   |
| ---- | ---- |

|   ![](img/3.jpg)   |   ![](img/4.jpg)   |
| ---- | ---- |

## How to use?

**Android Users**

1. First, download the software APK, install it on your mobile phone and open it
2. Be on the same network as the portable WiFi, open the control webpage, log in and enable the adb function
3. Use the ADB function of your computer or mobile phone to connect to the portable WiFi and install the APK into the portable WiFi device
4. Use remote control software such as Scrcpy to start zte-ufi-tools, set the gateway, start the service, turn off battery optimization, and enable notifications (to ensure smooth auto-start on boot)
5. Use your mobile phone to access the IP address of the portable WiFi, with the port being 2333, and then you can use it

**iOS Users**

> iOS users need to use the traditional method to open adb, connect to WiFi and enter http://192.168.0.1/index.html#usb_port to enable adb
> 
> After that, you can follow the **step 3** for Android users

Note: Whether the functions can be used depends on your device model and version. Currently, the version that I have tested to work perfectly is **MU300_ZYV1.0.0B09**

Note 2: Since there are no official interfaces for CPU usage, temperature, and memory usage, if you install this APK on your mobile phone, the temperature and usage data will be provided by your mobile phone, not the portable WiFi.

Download link: https://www.123684.com/s/7oa5Vv-dQLD3?pwd=CkSj

Extraction code: `CkSj`

API documentation: https://kanokano.cn/wp-content/uploads/2025/06/UFI-TOOLSAPI文档.html
