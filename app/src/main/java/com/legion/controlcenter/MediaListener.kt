package com.legion.controlcenter

import android.service.notification.NotificationListenerService

/** مجرد وجود الخدمة دي (مع صلاحية الوصول للإشعارات) بيسمح للوحة بقراءة جلسات الميديا والتحكم فيها. */
class MediaListener : NotificationListenerService()
