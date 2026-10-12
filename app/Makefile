TARGET := iphone:clang:12.4:9.0
ARCHS = armv7 arm64
INSTALL_TARGET_PROCESSES = Aria

include $(THEOS)/makefiles/common.mk

APPLICATION_NAME = Aria

Aria_FILES = $(wildcard Sources/*.m)
Aria_FRAMEWORKS = UIKit Foundation CoreGraphics QuartzCore AVFoundation MediaPlayer
Aria_CFLAGS = -fobjc-arc -Wunguarded-availability

include $(THEOS_MAKE_PATH)/application.mk
