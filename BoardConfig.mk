#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Liuqin is an APQ/Wi-Fi-only tablet.
TARGET_IS_TABLET := true

# Security patch level
VENDOR_SECURITY_PATCH := 2026-02-01

# Inherit from xiaomi sm8450-common
include device/xiaomi/sm8450-common/BoardConfigCommon.mk

# Inherit from the proprietary version
include vendor/xiaomi/liuqin/BoardConfigVendor.mk

DEVICE_PATH := device/xiaomi/liuqin

# Sepolicy
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/public
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor

# VINTF
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    $(DEVICE_PATH)/framework_compatibility_matrix.xml

# Audio
AUDIO_FEATURE_ENABLED_CIRRUS_CALIBRATION_RESISTANCE := true

# Kernel
device_second_stage_modules := \
    fpc1552.ko \
    xiaomi_touch_lcd.ko \
    nt36532_touch.ko \
    nanosic_driver.ko

device_vendor_dlkm_exclusive_modules := \
    ispv3_cam_dev.ko \
    ispv3_mfd_dev.ko

liuqin_incompatible_kernel_modules := \
    fpc1540.ko \
    xiaomi_touch.ko

BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := \
    $(filter-out $(liuqin_incompatible_kernel_modules),$(BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD))
BOARD_VENDOR_KERNEL_MODULES_LOAD := \
    $(filter-out $(liuqin_incompatible_kernel_modules),$(BOARD_VENDOR_KERNEL_MODULES_LOAD))
BOOT_KERNEL_MODULES := \
    $(filter-out $(liuqin_incompatible_kernel_modules),$(BOOT_KERNEL_MODULES))

BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD += $(device_second_stage_modules)
BOARD_VENDOR_KERNEL_MODULES_LOAD += \
    $(device_second_stage_modules) \
    $(device_vendor_dlkm_exclusive_modules)

BOOT_KERNEL_MODULES += $(device_second_stage_modules)

# Properties
TARGET_ODM_PROP += $(DEVICE_PATH)/properties/odm.prop
TARGET_PRODUCT_PROP += $(DEVICE_PATH)/properties/product.prop
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/properties/system.prop
TARGET_VENDOR_PROP += $(DEVICE_PATH)/properties/vendor.prop

# Screen density
TARGET_SCREEN_DENSITY := 400
