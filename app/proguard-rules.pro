# ViewPager's scroller is replaced by reflection so the page swipe speed can be adjusted.
-keepclassmembers class androidx.viewpager.widget.ViewPager {
    private android.widget.Scroller mScroller;
}
