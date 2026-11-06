package dev.kobbler.app.ui.reader.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackModelTest {

    @Test
    fun `manifest decodes every field and tolerates unknown keys`() {
        val manifest = PackJson.decodeFromString<Manifest>(
            """
            {"generation":1,"title":"Nineteen Eighty-Four","authors":["George Orwell"],"language":"en","rtl":true,
             "future_field":{"x":1},
             "chapters":[
               {"href":"OEBPS/text/ch01.xhtml","title":"Part One","weight":48213,"fixed_layout":{"side":"right","spreads":true}},
               {"href":"OEBPS/text/ch02.xhtml"}
             ],
             "toc":[{"title":"Part One","target":{"chapter":1},"children":[{"title":"1","target":{"chapter":1,"block":3,"offset":7}}]}],
             "styles":[{},{"size":1.2,"weight":700,"italic":true,"small_caps":true,"family":"Linux Libertine","align":"justify",
               "indent":-1.5,"line_height":1.4,"letter_spacing":0.05,"word_spacing":0.1,"color":"#333333","transform":"uppercase",
               "underline":true,"strike":true,"baseline":"super","hyphens":"manual","widows":3,"orphans":4,
               "dropcap":{"lines":3,"chars":1},"align_last":"center"},
               {"align":"inter-character","transform":"full-width"}],
             "fonts":[{"family":"Linux Libertine","weight":700,"italic":true,"src":"OEBPS/fonts/a.otf"},{"family":"B","src":"b.otf"}]}
            """,
        )
        assertEquals(1, manifest.generation)
        assertEquals("Nineteen Eighty-Four", manifest.title)
        assertEquals(listOf("George Orwell"), manifest.authors)
        assertEquals("en", manifest.language)
        assertEquals(true, manifest.rtl)
        assertEquals(
            listOf(
                ChapterEntry("OEBPS/text/ch01.xhtml", "Part One", 48213, FixedLayoutDto(PageSide.Right, spreads = true)),
                ChapterEntry("OEBPS/text/ch02.xhtml"),
            ),
            manifest.chapters,
        )
        assertEquals(
            listOf(TocNode("Part One", Position(1, 0, 0), listOf(TocNode("1", Position(1, 3, 7))))),
            manifest.toc,
        )
        assertEquals(PackTextStyle(), manifest.styles[0])
        assertEquals(
            PackTextStyle(
                size = 1.2f, weight = 700, italic = true, smallCaps = true, family = "Linux Libertine",
                align = TextAlignDto.Justify, indent = -1.5f, lineHeight = 1.4f, letterSpacing = 0.05f,
                wordSpacing = 0.1f, color = "#333333", transform = TextTransform.Uppercase, underline = true,
                strike = true, baseline = Baseline.Super, hyphens = Hyphenation.Manual, widows = 3, orphans = 4,
                dropcap = DropCap(lines = 3, chars = 1),
            ),
            manifest.styles[1],
        )
        assertEquals(PackTextStyle(), manifest.styles[2])
        assertEquals(
            listOf(FontFace("Linux Libertine", 700, true, "OEBPS/fonts/a.otf"), FontFace("B", 400, false, "b.otf")),
            manifest.fonts,
        )
    }

    @Test
    fun `chapter decodes every block kind`() {
        val chapter = PackJson.decodeFromString<PackChapter>(
            """
            {"blocks":[
              {"kind":"heading","level":2,"style":4,"spans":[{"text":"Chapter ","style":4},{"text":"One"}],
               "space_before":1.0,"inset_start":2.0,"inset_end":0.5,"frame":"quote","marker":"1.",
               "break_before":true,"keep_with_next":true,"avoid_break_inside":true},
              {"spans":[{"text":"It was a "},{"text":"bright","style":7,"link":{"position":{"chapter":2,"block":1}}},
                        {"text":" day","link":{"href":"https://example.com"}},{"text":"1","style":9,"note":"n3"}]},
              {"kind":"image","image":{"src":"OEBPS/images/fig1.jpg","alt":"Map","width":1200,"height":800},"frame":"figure"},
              {"kind":"rule"},
              {"kind":"table","table":{"rows":[{"header":true,"cells":[{"blocks":[{"spans":[{"text":"H"}]}],"colspan":2,"rowspan":3}]},
                                                {"cells":[{"blocks":[]}]}]}},
              {"kind":"fixed_page","fixed":{"image":{"src":"p.jpg"},"page_width":600,"page_height":914,
               "fragments":[{"top":0.12,"left":0.08,"width":310.0,"font_size":22.0,"color":"#000000","align":"start","spans":[{"text":"Hi"}]}]}},
              {"kind":"code","spans":[{"text":"a\n  b"}]},
              {"kind":"hologram","frame":"sidebar","spans":[{"text":"x"}]}
            ],
            "anchors":{"sec2":{"block":14},"top":{"block":1,"offset":4}},
            "notes":{"n3":[{"spans":[{"text":"A note."}]}]}}
            """,
        )
        val (heading, para, image, rule, table, fixed, code, unknown) = chapter.blocks
        assertEquals(
            Block(
                kind = BlockKind.Heading, level = 2, style = 4,
                spans = listOf(Span("Chapter ", 4), Span("One")),
                spaceBefore = 1f, insetStart = 2f, insetEnd = 0.5f, frame = Frame.Quote, marker = "1.",
                breakBefore = true, keepWithNext = true, avoidBreakInside = true,
            ),
            heading,
        )
        assertEquals("Chapter One", heading.text)
        assertEquals(
            listOf(
                Span("It was a "),
                Span("bright", 7, link = Link(position = Position(2, 1, 0))),
                Span(" day", link = Link(href = "https://example.com")),
                Span("1", 9, note = "n3"),
            ),
            para.spans,
        )
        assertEquals(BlockKind.Text, para.kind)
        assertEquals(ImageRef("OEBPS/images/fig1.jpg", "Map", 1200, 800), image.image)
        assertEquals(Frame.Figure, image.frame)
        assertEquals(BlockKind.Rule, rule.kind)
        assertEquals(
            Table(
                listOf(
                    TableRow(true, listOf(TableCell(listOf(Block(spans = listOf(Span("H")))), colspan = 2, rowspan = 3))),
                    TableRow(false, listOf(TableCell())),
                ),
            ),
            table.table,
        )
        assertEquals(
            FixedPageDto(
                ImageRef("p.jpg"), 600f, 914f,
                listOf(Fragment(0.12f, 0.08f, 310f, 22f, "#000000", TextAlignDto.Start, listOf(Span("Hi")))),
            ),
            fixed.fixed,
        )
        assertEquals("a\n  b", code.text)
        assertEquals(BlockKind.Text, unknown.kind)
        assertNull(unknown.frame)
        assertEquals(mapOf("sec2" to BlockOffset(14, 0), "top" to BlockOffset(1, 4)), chapter.anchors)
        assertEquals(mapOf("n3" to listOf(Block(spans = listOf(Span("A note."))))), chapter.notes)
    }

    @Test
    fun `spanAt maps utf16 offsets to their span`() {
        val block = Block(spans = listOf(Span("ab"), Span(""), Span("😀c", note = "n"), Span("d")))
        val cases = listOf(-1 to null, 0 to "ab", 1 to "ab", 2 to "😀c", 4 to "😀c", 5 to "d", 6 to null)
        for ((offset, expected) in cases) assertEquals(expected, block.spanAt(offset)?.text, "offset $offset")
        assertEquals(2, block.spanStart(2))
    }

    @Test
    fun `blocks decode the book's own boxes, rules and image widths`() {
        val chapter = PackJson.decodeFromString<PackChapter>(
            """
            {"blocks":[
              {"spans":[{"text":"Boxed"}],"frame":"quote","frame_plain":true,
               "containers":[{"id":3,"inset_start":2.0,"inset_end":1.5,"space_top":0.625,"space_bottom":0.5,
                 "top":{"width":0.125,"style":"dashed","color":"#333333"},"left":{"width":0.2,"style":"double"},
                 "right":{"width":0.1,"style":"groove"},"background":"#dddddd"}]},
              {"kind":"rule","rule":{"line":{"width":0.125,"style":"dotted"},"length":0.2}},
              {"kind":"image","image":{"src":"a.png","column_width":0.4}}
            ]}
            """,
        )
        val boxed = chapter.blocks[0]
        assertEquals(true, boxed.framePlain)
        assertEquals(
            listOf(
                Container(
                    id = 3, insetStart = 2f, insetEnd = 1.5f, spaceTop = 0.625f, spaceBottom = 0.5f,
                    top = BorderLine(0.125f, LineStyle.Dashed, "#333333"),
                    left = BorderLine(0.2f, LineStyle.Double),
                    right = BorderLine(0.1f, LineStyle.Solid),
                    background = "#dddddd",
                ),
            ),
            boxed.containers,
        )
        assertEquals(RuleStyle(BorderLine(0.125f, LineStyle.Dotted), length = 0.2f), chapter.blocks[1].rule)
        assertEquals(0.4f, chapter.blocks[2].image?.columnWidth)
    }

    @Test
    fun `floats and clears decode`() {
        val chapter = PackJson.decodeFromString<PackChapter>(
            """
            {"blocks":[
              {"kind":"image","image":{"src":"a.png","column_width":0.4},"float":{"side":"right","gap":0.25},"clear":"left"},
              {"kind":"image","image":{"src":"b.png"},"float":{"side":"left"}},
              {"spans":[{"text":"x"}],"clear":"both"}
            ]}
            """,
        )
        assertEquals(BlockFloat(FloatSide.Right, 0.25f), chapter.blocks[0].float)
        assertEquals(Clear.Left, chapter.blocks[0].clear)
        assertEquals(BlockFloat(FloatSide.Left), chapter.blocks[1].float)
        assertEquals(Clear.Both, chapter.blocks[2].clear)
        assertEquals(null, chapter.blocks[2].float)
    }

    @Test
    fun `round two of the book's css decodes`() {
        val chapter = PackJson.decodeFromString<PackChapter>(
            """
            {"blocks":[
              {"spans":[{"text":"x"}],"centred":{"em":20.0},
               "containers":[{"id":1,"radius":[1.0,1.0,0.0,0.0],"centred":{"fraction":0.5},"inner":2.25}]},
              {"kind":"table","table":{"collapse":true,"rows":[{"cells":[{"blocks":[],"colspan":1,"rowspan":1,
                "borders":{"top":{"width":0.125,"style":"double"},"left":{"width":0.063,"style":"solid","color":"#000000"}}}]}]}}
            ]}
            """,
        )
        val block = chapter.blocks[0]
        assertEquals(Measure(em = 20f), block.centred)
        assertEquals(Container(id = 1, radius = listOf(1f, 1f, 0f, 0f), centred = Measure(fraction = 0.5f), inner = 2.25f), block.containers[0])
        val table = chapter.blocks[1].table!!
        assertEquals(true, table.collapse)
        assertEquals(
            Borders(top = BorderLine(0.125f, LineStyle.Double), left = BorderLine(0.063f, LineStyle.Solid, "#000000")),
            table.rows[0].cells[0].borders,
        )
        val style = PackJson.decodeFromString<PackTextStyle>(
            """{"shift":-0.25,"background":"#ffff00","borders":{"bottom":{"width":0.063,"style":"dashed"}}}""",
        )
        assertEquals(
            PackTextStyle(shift = -0.25f, background = "#ffff00", borders = Borders(bottom = BorderLine(0.063f, LineStyle.Dashed))),
            style,
        )
        val ornament = PackJson.decodeFromString<RuleStyle>(
            """{"height":1.0,"image":{"src":"OEBPS/images/stars.png","width":1200,"height":800,"repeat":true}}""",
        )
        assertEquals(RuleStyle(height = 1f, image = RuleImage("OEBPS/images/stars.png", 1200, 800, repeat = true)), ornament)
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
private operator fun <T> List<T>.component7(): T = this[6]
private operator fun <T> List<T>.component8(): T = this[7]
