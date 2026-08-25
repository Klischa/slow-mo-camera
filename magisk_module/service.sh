#!/system/bin/sh
# Late service script
sleep 5
setprop persist.vendor.camera.privapp.list com.klischa.slowmocamera,com.transsion.camera,com.infinix.camera,com.android.camera,com.google.android.GoogleCamera
setprop persist.vendor.camera.highspeed.enable 1
setprop persist.vendor.camera.p1.highspeed 1
setprop persist.vendor.camera.support.60fps 1
setprop persist.vendor.camera.support.120fps 1
setprop persist.vendor.camera.support.240fps 1
setprop persist.vendor.camera.sensor.60fps 1
setprop persist.vendor.camera.sensor.120fps 1
setprop persist.vendor.camera.sensor.240fps 1
