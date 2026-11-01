package app.ioshome.launcher

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import java.util.WeakHashMap

/** Spring-driven view property animation helpers. At most one running spring per view property. */
object Springs {
    private val running = WeakHashMap<View, HashMap<String, SpringAnimation>>()

    private fun spring(
        view: View,
        name: String,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        stiffness: Float,
        damping: Float
    ) {
        val perView = running.getOrPut(view) { HashMap() }
        perView[name]?.cancel()
        val anim = SpringAnimation(view, property, target)
        anim.spring.setStiffness(stiffness)
        anim.spring.setDampingRatio(damping)
        perView[name] = anim
        anim.start()
    }

    fun cancelAll(view: View) {
        val perView = running[view] ?: return
        for (anim in perView.values) anim.cancel()
        perView.clear()
    }

    /** Moves the view's translation to ([dx], [dy]); animated with a critically damped spring if [animate]. */
    fun move(view: View, dx: Float, dy: Float, animate: Boolean) {
        if (!animate) {
            cancelAll(view)
            view.translationX = dx
            view.translationY = dy
            return
        }
        spring(view, "tx", DynamicAnimation.TRANSLATION_X, dx, 650f, 0.85f)
        spring(view, "ty", DynamicAnimation.TRANSLATION_Y, dy, 650f, 0.85f)
    }

    /** Scale from [from] to 1 with a visible but short bounce (used when a dropped icon settles). */
    fun settle(view: View, from: Float) {
        view.scaleX = from
        view.scaleY = from
        spring(view, "sx", DynamicAnimation.SCALE_X, 1f, 480f, 0.5f)
        spring(view, "sy", DynamicAnimation.SCALE_Y, 1f, 480f, 0.5f)
    }

    fun scaleTo(view: View, target: Float) {
        spring(view, "sx", DynamicAnimation.SCALE_X, target, 600f, 0.75f)
        spring(view, "sy", DynamicAnimation.SCALE_Y, target, 600f, 0.75f)
    }
}
