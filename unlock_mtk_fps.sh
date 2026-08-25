#!/system/bin/sh
# Скрипт разблокировки 60/120/240 FPS и High-Speed HAL для MediaTek Helio G99 / Infinix Note 30
# Требуются Root-права (Magisk / KernelSU / APatch)

echo "=== Разблокировка MediaTek Camera2 High-Speed HAL ==="

# 1. Добавление Slow-Mo Camera и стандартных имен в белый список PrivApp камеры MediaTek
setprop persist.vendor.camera.privapp.list com.klischa.slowmocamera,com.transsion.camera,com.infinix.camera,com.android.camera,com.google.android.GoogleCamera
setprop vendor.camera.privapp.list com.klischa.slowmocamera,com.transsion.camera,com.infinix.camera,com.android.camera

# 2. Активация высокоскоростного режима и FPS (60, 120, 240)
setprop persist.vendor.camera.highspeed.enable 1
setprop persist.vendor.camera.p1.highspeed 1
setprop persist.vendor.camera.support.60fps 1
setprop persist.vendor.camera.support.120fps 1
setprop persist.vendor.camera.support.240fps 1
setprop persist.vendor.camera.sensor.60fps 1
setprop persist.vendor.camera.sensor.120fps 1
setprop persist.vendor.camera.sensor.240fps 1

# 3. Разблокировка Slow Motion и AUX сенсоров
setprop persist.vendor.camera.feature.slowmotion 1
setprop persist.vendor.camera.expose.aux 1
setprop vendor.camera.highspeed.enable 1
setprop ro.vendor.camera.sensor.slowmotion 1

# 4. Перезапуск службы камер Android для применения параметров
echo "Перезапуск cameraserver..."
killall cameraserver android.hardware.camera.provider@2.4-service-mediatek 2>/dev/null || pkill -f camera

echo "Готово! Запустите Slow-Mo Camera и проверьте работу."
