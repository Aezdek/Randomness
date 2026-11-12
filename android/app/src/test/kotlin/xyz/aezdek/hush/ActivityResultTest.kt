package xyz.aezdek.hush

import android.Manifest
import android.app.Application
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hush's activities are FragmentActivities (the fingerprint prompt needs one). An old Fragment library there rejects
 * the request codes the Activity Result API hands out, so asking for a photo, the camera or notifications closed the
 * app with "Can only use lower 16 bits for requestCode". These launches must go through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityResultTest {
    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    @Test
    fun askingForAPermissionDoesNotCrash() {
        activity.activityResultRegistry.register("notifications", ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
        activity.activityResultRegistry.register("call", ActivityResultContracts.RequestMultiplePermissions()) {}
            .launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun openingThePhotoPickerDoesNotCrash() {
        activity.activityResultRegistry.register("photos", ActivityResultContracts.PickMultipleVisualMedia(10)) {}
            .launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        assertNotNull(shadowOf(activity).nextStartedActivityForResult)
    }
}
