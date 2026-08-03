#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from liuqin device
$(call inherit-product, device/xiaomi/liuqin/device.mk)

# Inherit from the Wi-Fi-only tablet Lineage configuration
$(call inherit-product, vendor/lineage/config/common_full_tablet_wifionly.mk)

PRODUCT_NAME := lineage_liuqin
PRODUCT_DEVICE := liuqin
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_BRAND := Xiaomi
PRODUCT_MODEL := 23046RP50C

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="liuqin-user 15 AQ3A.250226.002 OS3.0.7.0.VMYCNXM release-keys" \
    BuildFingerprint=Xiaomi/liuqin/miproduct:15/AQ3A.250226.002/OS3.0.7.0.VMYCNXM:user/release-keys \
    DeviceProduct=liuqin \
    SystemName=liuqin

PRODUCT_GMS_CLIENTID_BASE := android-xiaomi
