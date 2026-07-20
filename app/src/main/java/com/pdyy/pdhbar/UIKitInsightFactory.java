package com.pdyy.pdhbar;

import com.uikit.insight.NewInsightKt;
import com.uikit.insight.NewUIInsightPlay;
import com.uikit.insight.UIInsightCss;
import com.uikit.insight.UIInsightPlayConfig;

/** Java bridge for the AAR's public JVM factory, which is hidden by its Kotlin metadata. */
final class UIKitInsightFactory {
    private UIKitInsightFactory() {}

    static NewUIInsightPlay create(UIInsightPlayConfig config, UIInsightCss css) {
        return NewInsightKt.NewInsight(config, css);
    }
}
