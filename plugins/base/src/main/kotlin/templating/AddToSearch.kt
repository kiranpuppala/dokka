package org.jetbrains.dokka.base.templating

import org.jetbrains.dokka.base.renderers.html.SearchRecord

class AddToSearch(val moduleName: String, val elements: List<SearchRecord>): Command