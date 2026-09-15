package com.pdyy.pdhbar.runtime

/** Business rejections are terminal; network/server failures may be retried by the queue. */
internal fun isTerminalHttpOutcome(statusCode: Int, businessCode: Int?): Boolean =
    statusCode in 200..299 || statusCode in 400..499 ||
        (businessCode != null && businessCode != 200)