package com.bilipai.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.Color
import java.awt.Graphics
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputMethodEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.*

/** Opt-in read-only evidence of actual Swing painting; this never requests a repaint. */
internal class DesktopInlineEmotePaintProbe(private val release: () -> Unit) : AutoCloseable {
    data class Receipt(val sequence: Long, val width: Int, val height: Int,
        val visibleX: Int, val visibleY: Int, val visibleWidth: Int, val visibleHeight: Int,
        val scrollIdentity: Int, val scrollWidth: Int, val scrollHeight: Int,
        val viewportWidth: Int, val viewportHeight: Int, val completedAtNanos: Long)
    private var sequence = 0L
    private var closed = false
    private var completed: Receipt? = null
    fun snapshot(): Receipt? { check(SwingUtilities.isEventDispatchThread()); return if (closed) null else completed }
    internal fun painted(pane: DesktopInlineEmotePane, clip: java.awt.Shape?) {
        check(SwingUtilities.isEventDispatchThread())
        val visible = pane.visibleRect
        if (closed || visible.isEmpty || clip == null || !clip.contains(visible)) return
        val viewport = pane.parent as? javax.swing.JViewport ?: return
        val scroll = viewport.parent as? javax.swing.JScrollPane ?: return
        if (scroll.width <= 0 || scroll.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return
        completed = Receipt(++sequence, pane.width, pane.height, visible.x, visible.y, visible.width, visible.height,
            System.identityHashCode(scroll), scroll.width, scroll.height, viewport.width, viewport.height, System.nanoTime())
    }
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (!closed) { closed = true; release() }
    }
}

/** A display span covers the existing UTF-16 code; the document is never replaced
 * with an object-replacement character. Draft, IME and clipboard use this document. */
internal class DesktopInlineEmotePane(
    private val tokenPattern: Regex,
    private val owned: () -> Boolean,
    private val clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
) : JTextPane() {
    var valueChanged: (TextFieldValue) -> Unit = {}
    var beginEditing: () -> Unit = {}
    var requestImage: (String) -> Unit = {}
    var hint = ""
    var hintColor = Color.GRAY
    private var urls: Map<String,String> = emptyMap()
    private val icons = linkedMapOf<String,Icon>()
    private var syncing = false
    private var rendering = false
    private var changing = false
    private var queued = false
    private var panelReadOnly = false
    private var suppliedComposition: TextRange? = null
    private var previousCaret = 0
    private var closed = false
    private var paintProbe: DesktopInlineEmotePaintProbe? = null
    internal fun observeCompletedPaints(): DesktopInlineEmotePaintProbe {
        check(SwingUtilities.isEventDispatchThread() && !closed && paintProbe == null && owned())
        lateinit var probe: DesktopInlineEmotePaintProbe
        probe = DesktopInlineEmotePaintProbe { if (paintProbe === probe) paintProbe = null }
        paintProbe = probe
        return probe
    }
    private var hasVisibleAnimation = false
    private val animationTimer=Timer(33) {
        if(!closed&&owned()&&isShowing&&hasVisibleAnimation)repaint()
        else (it.source as Timer).stop()
    }
    init {
        isOpaque=false; margin=Insets(0,0,0,0)
        (document as AbstractDocument).documentFilter=object:DocumentFilter() {
            override fun insertString(fb:FilterBypass,offset:Int,string:String?,attr:AttributeSet?) = replace(fb,offset,0,string,attr)
            override fun remove(fb:FilterBypass,offset:Int,length:Int) = replace(fb,offset,length,"",null)
            override fun replace(fb:FilterBypass,offset:Int,length:Int,text:String?,attrs:AttributeSet?) {
                if (!syncing && (!canEdit())) return
                val incoming=text.orEmpty();val available=(1000-fb.document.length+length).coerceAtLeast(0)
                var count=minOf(available,incoming.length)
                if(count>0 && count<incoming.length && incoming[count-1].isHighSurrogate()) count--
                var start=offset;var end=offset+length
                // DEL and forward DEL are atomic only for successfully rendered spans.
                if(!syncing && incoming.isEmpty() && length==1 && caret.dot==caret.mark) {
                    spanAt(offset)?.let {span->
                        if(caret.dot==span.second || caret.dot==span.first){start=span.first;end=span.second}
                    }
                }
                val clean=if(attrs==null)SimpleAttributeSet()else SimpleAttributeSet(attrs)
                clean.removeAttribute(StyleConstants.IconAttribute)
                clean.removeAttribute(AbstractDocument.ElementNameAttribute)
                changing=true
                try{fb.replace(start,end-start,incoming.substring(0,count),clean)}finally{changing=false}
                if(!syncing) schedulePublication()
            }
        }
        document.addDocumentListener(object:DocumentListener {
            override fun insertUpdate(e:DocumentEvent){if(!syncing)schedulePublication()}
            override fun removeUpdate(e:DocumentEvent){if(!syncing)schedulePublication()}
            override fun changedUpdate(e:DocumentEvent){}
        })
        navigationFilter=object:NavigationFilter() {
            override fun setDot(fb:FilterBypass,dot:Int,bias:Position.Bias){fb.setDot(boundary(dot,dot>=previousCaret),bias)}
            override fun moveDot(fb:FilterBypass,dot:Int,bias:Position.Bias){fb.moveDot(boundary(dot,dot>caret.mark),bias)}
        }
        addCaretListener {
            if(!syncing&&!rendering&&!changing){previousCaret=caret.dot;schedulePublication()}
        }
        addMouseListener(object:MouseAdapter(){override fun mousePressed(e:MouseEvent){
            if(isEnabled&&panelReadOnly&&owned()&&!closed){panelReadOnly=false;isEditable=true;beginEditing()}
        }})
        transferHandler=object:TransferHandler(){
            override fun canImport(support:TransferSupport)=canEdit()&&support.isDataFlavorSupported(DataFlavor.stringFlavor)
            override fun importData(support:TransferSupport):Boolean {
                if(!canImport(support))return false
                val raw=runCatching{support.transferable.getTransferData(DataFlavor.stringFlavor) as? String}.getOrNull()?:return false
                replaceSelection(raw);return true
            }
        }
    }
    private fun canEdit()=!closed&&owned()&&isEnabled&&!panelReadOnly
    fun rawValue():TextFieldValue {
        val composed=compositionRange()?:suppliedComposition
        return TextFieldValue(document.getText(0,document.length),TextRange(caret.mark,caret.dot),composed)
    }
    fun bind(value:TextFieldValue,catalog:Map<String,String>,enabled:Boolean,readOnly:Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if(closed)return
        val changed=document.getText(0,document.length)!=value.text
        syncing=true
        try {
            urls=catalog;isEnabled=enabled;panelReadOnly=readOnly;isEditable=enabled&&!readOnly
            suppliedComposition=value.composition
            if(changed)(document as AbstractDocument).replace(0,document.length,value.text,null)
            caret.setDot(value.selection.start.coerceIn(0,document.length))
            caret.moveDot(value.selection.end.coerceIn(0,document.length))
            previousCaret=caret.dot
        } finally{syncing=false}
        renderEmotes();repaint()
    }
    private fun schedulePublication() {
        if(queued||closed)return;queued=true
        SwingUtilities.invokeLater {
            queued=false
            if(!closed&&owned()) {
                suppliedComposition=compositionRange()
                renderEmotes();valueChanged(rawValue());repaint()
            }
        }
    }
    private fun compositionRange():TextRange? {
        var at=0;var start=-1;var end=-1
        while(at<document.length) {
            val element=styledDocument.getCharacterElement(at)
            if(element.attributes.isDefined(StyleConstants.ComposedTextAttribute)) {
                if(start<0)start=element.startOffset;end=minOf(document.length,element.endOffset)
            }
            at=maxOf(at+1,element.endOffset)
        }
        return if(start>=0&&end>start)TextRange(start,end)else null
    }
    private fun spanAt(position:Int):Pair<Int,Int>? {
        if(position !in 0 until document.length)return null
        val element=styledDocument.getCharacterElement(position)
        if(StyleConstants.getIcon(element.attributes)==null)return null
        return element.startOffset to minOf(element.endOffset,document.length)
    }
    private fun boundary(position:Int,towardEnd:Boolean):Int {
        if(syncing||changing||rendering)return position
        val span=spanAt(position)?:return position
        return if(position>span.first&&position<span.second){if(towardEnd)span.second else span.first}else position
    }
    private fun renderEmotes() {
        if(closed||rendering)return
        rendering=true
        hasVisibleAnimation=false
        try {
            // Remove just the drawing attributes. Preserve native IME attributes.
            var at=0
            while(at<document.length) {
                val element=styledDocument.getCharacterElement(at);val end=minOf(document.length,element.endOffset)
                if(StyleConstants.getIcon(element.attributes)!=null) {
                    val attrs=SimpleAttributeSet(element.attributes)
                    attrs.removeAttribute(StyleConstants.IconAttribute);attrs.removeAttribute(AbstractDocument.ElementNameAttribute)
                    styledDocument.setCharacterAttributes(at,end-at,attrs,true)
                }
                at=maxOf(at+1,end)
            }
            val raw=document.getText(0,document.length);val composition=compositionRange()?:suppliedComposition
            tokenPattern.findAll(raw).forEach {match->
                val url=urls[match.value]?.takeIf(String::isNotBlank)?:return@forEach
                val start=match.range.first;val end=match.range.last+1
                if(composition!=null&&composition.min<end&&composition.max>start)return@forEach
                val icon=icons[url]
                if(icon==null){requestImage(url);return@forEach}
                val attrs=SimpleAttributeSet();StyleConstants.setIcon(attrs,icon)
                styledDocument.setCharacterAttributes(start,end-start,attrs,false)
                if(icon is DesktopInlineAnimatedEmoteIcon)hasVisibleAnimation=true
            }
        } finally{rendering=false}
    }
    fun imageReady(url:String,icon:Icon) {
        check(SwingUtilities.isEventDispatchThread())
        if(closed||!owned()){release(icon);return}
        val old=icons.put(url,icon);if(old!==icon&&old!=null)release(old)
        if(rendering){SwingUtilities.invokeLater {if(!closed&&owned()){renderEmotes();revalidate();repaint();if(isShowing&&hasVisibleAnimation)animationTimer.start()}};return}
        renderEmotes();revalidate();repaint()
        if(isShowing&&hasVisibleAnimation)animationTimer.start()
    }
    fun replaceAllRaw(raw:String) {
        if(!canEdit())return
        (document as AbstractDocument).replace(0,document.length,raw,null);caretPosition=document.length
    }
    override fun replaceSelection(content:String?) {if(canEdit())super.replaceSelection(content)}
    override fun copy() {
        if(closed||!owned())return
        val start=minOf(caret.dot,caret.mark);val end=maxOf(caret.dot,caret.mark)
        clipboard().setContents(StringSelection(document.getText(start,end-start)),null)
    }
    override fun cut(){copy();if(canEdit())replaceSelection("")}
    override fun paste(){if(canEdit()) {
        val raw=runCatching{clipboard().getData(DataFlavor.stringFlavor) as? String}.getOrNull()?:return
        replaceSelection(raw)
    }}
    override fun processInputMethodEvent(e:InputMethodEvent) {
        if(!canEdit()){e.consume();return};super.processInputMethodEvent(e);schedulePublication()
    }
    override fun paint(g: Graphics) {
        super.paint(g)
        val probe = paintProbe
        if (probe != null && !closed && isShowing && !isPaintingForPrint && owned())
            probe.painted(this, g.clip)
    }
    override fun paintComponent(g:Graphics) {
        super.paintComponent(g)
        if(document.length==0&&hint.isNotEmpty()) {g.color=hintColor;g.font=font;g.drawString(hint,margin.left,margin.top+g.fontMetrics.ascent)}
    }
    private fun release(icon:Icon){if(icon is AutoCloseable)icon.close()else (icon as? ImageIcon)?.image?.flush()}
    override fun addNotify(){super.addNotify();if(hasVisibleAnimation)animationTimer.start()}
    override fun removeNotify(){animationTimer.stop();super.removeNotify()}
    fun retire(){check(SwingUtilities.isEventDispatchThread());closed=true;paintProbe?.close();animationTimer.stop();icons.values.forEach(::release);icons.clear();requestImage={};valueChanged={};beginEditing={}}
}
