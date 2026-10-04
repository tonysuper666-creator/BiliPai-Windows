package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasLexer
import com.android.purebilibili.danmaku.parser.bas.BasParseException
import com.android.purebilibili.danmaku.parser.bas.BasToken
import com.android.purebilibili.danmaku.parser.bas.BasTokenKind

/** Resource admission only. The fixed original parser still owns all BAS grammar and typing.
 * Summaries count an expanded value DAG without constructing its referenced arrays/objects.
 */
internal object DesktopBasParseBudget {
    const val MAX_SOURCE_CHARS=16_384
    private const val MAX_TOKENS=4_096
    private const val MAX_DEPTH=64
    private const val MAX_EXPANDED_NODES=8_192
    private const val MAX_PROGRAM_WORK=16_384
    private val types=setOf("text","button","path")
    data class Estimate(val sourceChars:Int,val workUnits:Int)
    private data class Edge(val count:Int,val depth:Int)
    private data class Summary(val nodes:Int,val depth:Int,val references:Map<String,Edge>)
    private data class Bound(val nodes:Int,val depth:Int)
    private data class Template(val parameters:LinkedHashMap<String,Summary>,val body:Summary,val keys:Set<String>)

    fun estimate(source:String):Estimate? {
        if(source.isBlank() || source.length>MAX_SOURCE_CHARS)return null
        return try {
            val tokens=ArrayList<BasToken>()
            val closings=hashMapOf<Int,Int>()
            val stack=ArrayList<Pair<String,Int>>()
            val lexer=BasLexer(source)
            var objectWords=0
            while(true) {
                val token=lexer.next()
                if(token.kind==BasTokenKind.END)break
                if(tokens.size>=MAX_TOKENS)return null
                val index=tokens.size;tokens+=token
                if(token.kind==BasTokenKind.ID) {
                    if(token.text in types && ++objectWords>256)return null
                }
                if(token.kind==BasTokenKind.SYMBOL)when(token.text) {
                    "{","(","[" -> {stack+=token.text to index;if(stack.size>MAX_DEPTH)return null}
                    "}",")","]" -> {
                        val open=stack.removeLastOrNull() ?: return null
                        if(open.first!=when(token.text){"}"->"{";")"->"(";else->"["})return null
                        closings[open.second]=index
                    }
                }
            }
            if(stack.isNotEmpty())return null
            // Token parsing, every copied attribute map, bound value graph and final
            // default/attribute merge are charged separately. Clone values are shared,
            // but their outer maps are copied by the original objectExpression().
            var work=tokens.size.toLong()
            val templates=hashMapOf<String,Template>()
            val objects=linkedMapOf<String,Set<String>>()
            var generated=0
            fun charge(amount:Int):Boolean {work+=amount;return work<=MAX_PROGRAM_WORK}
            fun temporary(keys:Set<String>):String {
                var name:String
                do {name="@bas_${generated++}"} while(name in objects || name in templates)
                objects[name]=keys
                return name
            }
            fun expression(start:Int):Pair<String,Int>? {
                if(tokens.getOrNull(start)?.symbol("(")==true) {
                    val inner=expression(start+1) ?: return null
                    var next=inner.second
                    if(tokens.getOrNull(next)?.symbol("{")==true) {
                        val close=closings[next] ?: return null
                        val keys=objects.getValue(inner.first)+attributeKeys(tokens,next+1,close)
                        if(!charge(keys.size))return null
                        objects[inner.first]=keys;next=close+1
                    }
                    if(tokens.getOrNull(next)?.symbol(")")!=true)return null
                    return inner.first to next+1
                }
                val base=tokens.getOrNull(start)?.takeIf {it.kind==BasTokenKind.ID}?.text ?: return null
                val next=start+1
                if(tokens.getOrNull(next)?.symbol("(")==true) {
                    val template=templates[base] ?: return null
                    val close=closings[next] ?: return null
                    val actual=arguments(tokens,next+1,close,template.parameters) ?: return null
                    val result=bound(template.body,actual) ?: return null
                    if(!charge(result.nodes))return null
                    return temporary(template.keys) to close+1
                }
                if(tokens.getOrNull(next)?.symbol("{")!=true)return null
                val close=closings[next] ?: return null
                val keys=attributeKeys(tokens,next+1,close)
                val result=if(base in types)keys else {
                    val prior=objects[base] ?: return null
                    (prior+keys).also {if(!charge(it.size))return null}
                }
                return temporary(result) to close+1
            }
            var index=0
            while(index<tokens.size) {
                if(tokens[index].identifier("def") && tokens.getOrNull(index+1)?.text in types &&
                    tokens.getOrNull(index+2)?.kind==BasTokenKind.ID) {
                    val name=tokens[index+2].text
                    val open=index+3
                    if(tokens.getOrNull(open)?.symbol("(")==true) {
                        val close=closings[open] ?: return null
                        val parameters=parameters(tokens,open+1,close) ?: return null
                        val bodyOpen=close+1
                        if(tokens.getOrNull(bodyOpen)?.symbol("{")!=true)return null
                        val bodyClose=closings[bodyOpen] ?: return null
                        val body=summary(tokens,bodyOpen,bodyClose+1)
                        // Even an unused cyclic/explosive default is refused. All
                        // parameter dependencies share one bounded DFS memo.
                        if(bound(body,parameters,validateDefaults=true)==null)return null
                        templates[name]=Template(parameters,body,attributeKeys(tokens,bodyOpen+1,bodyClose))
                        objects.remove(name);index=bodyClose+1
                    } else {
                        if(tokens.getOrNull(open)?.symbol("{")!=true)return null
                        val close=closings[open] ?: return null
                        objects[name]=attributeKeys(tokens,open+1,close)
                        templates.remove(name);index=close+1
                    }
                    continue
                }
                if(tokens[index].identifier("let")) {
                    val name=tokens.getOrNull(index+1)?.takeIf {it.kind==BasTokenKind.ID}?.text ?: return null
                    if(tokens.getOrNull(index+2)?.symbol("=")!=true)return null
                    val result=expression(index+3) ?: return null
                    objects[name]=objects.remove(result.first) ?: return null
                    templates.remove(name);index=result.second;continue
                }
                if(tokens[index].identifier("set") && tokens.getOrNull(index+1)?.symbol("(")==true) {
                    val result=expression(index+2) ?: return null
                    if(tokens.getOrNull(result.second)?.symbol(")")!=true)return null
                    index=result.second+1;continue
                }
                index++
            }
            // Match the wrapper's final element limit before originals allocate
            // their default maps and joined text for an already rejected scene.
            if(objects.size>256)return null
            for(keys in objects.values)if(!charge(20+keys.size))return null
            if(work>MAX_PROGRAM_WORK)null else Estimate(source.length,work.toInt())
        } catch(_:BasParseException) {null}
    }

    private fun BasToken.symbol(value:String)=kind==BasTokenKind.SYMBOL && text==value
    private fun BasToken.identifier(value:String)=kind==BasTokenKind.ID && text==value

    /** Only outer keys are copied by a clone; nested typed values retain their identity. */
    private fun attributeKeys(tokens:List<BasToken>,start:Int,end:Int):Set<String> {
        val result=linkedSetOf<String>();var depth=0
        for(index in start until end) {
            val token=tokens[index]
            if(depth==0 && token.kind==BasTokenKind.ID && tokens.getOrNull(index+1)?.symbol("=")==true)result+=token.text
            if(token.kind==BasTokenKind.SYMBOL)when(token.text){"{","[","("->depth++;"}","]",")"->depth--}
        }
        return result
    }

    private fun summary(tokens:List<BasToken>,start:Int,end:Int):Summary {
        var nodes=0;var depth=0;var maximum=0
        val references=hashMapOf<String,Edge>()
        for(index in start until end) {
            val token=tokens[index]
            when(token.kind) {
                BasTokenKind.STRING,BasTokenKind.NUMBER,BasTokenKind.HEX,BasTokenKind.TIME -> {nodes++;maximum=maxOf(maximum,depth+1)}
                BasTokenKind.SYMBOL -> when(token.text) {
                    "{","[" -> {nodes++;depth++;maximum=maxOf(maximum,depth)}
                    "}","]" -> depth--
                }
                BasTokenKind.ID -> {
                    val next=tokens.getOrNull(index+1)
                    // Property/argument keys and object type tags are not references.
                    if(next?.symbol("=")!=true && next?.symbol("{")!=true) {
                        val previous=references[token.text]
                        references[token.text]=Edge((previous?.count ?: 0)+1,maxOf(previous?.depth ?: 0,depth))
                    }
                }
                else -> Unit
            }
        }
        return Summary(nodes,maximum,references)
    }

    private fun valueEnd(tokens:List<BasToken>,start:Int,end:Int,argument:Boolean):Int {
        var depth=0;var index=start
        while(index<end) {
            val token=tokens[index]
            if(depth==0 && (token.symbol(if(argument)"," else ";") ||
                !argument && index>start && token.kind==BasTokenKind.ID && tokens.getOrNull(index+1)?.symbol("=")==true))break
            if(token.kind==BasTokenKind.SYMBOL)when(token.text){"{","[","("->depth++;"}","]",")"->depth--}
            index++
        }
        return index
    }

    private fun parameters(tokens:List<BasToken>,start:Int,end:Int):LinkedHashMap<String,Summary>? {
        val result=linkedMapOf<String,Summary>();var index=start
        while(index<end) {
            val name=tokens[index].takeIf {it.kind==BasTokenKind.ID}?.text ?: return null
            if(tokens.getOrNull(index+1)?.symbol("=")!=true)return null
            val valueStart=index+2;val valueEnd=valueEnd(tokens,valueStart,end,false)
            if(valueEnd==valueStart)return null
            result[name]=summary(tokens,valueStart,valueEnd)
            index=valueEnd
            if(tokens.getOrNull(index)?.symbol(";")==true)index++
        }
        return result
    }

    private fun arguments(tokens:List<BasToken>,start:Int,end:Int,defaults:LinkedHashMap<String,Summary>):LinkedHashMap<String,Summary>? {
        val named=linkedMapOf<String,Summary>();val positional=ArrayList<Summary>();var index=start
        while(index<end) {
            val name=if(tokens[index].kind==BasTokenKind.ID && tokens.getOrNull(index+1)?.symbol("=")==true)tokens[index].text else null
            if(name!=null && name !in defaults)return null
            val valueStart=index+if(name==null)0 else 2
            val valueEnd=valueEnd(tokens,valueStart,end,true)
            if(valueEnd==valueStart)return null
            val value=summary(tokens,valueStart,valueEnd)
            if(name==null)positional+=value else named[name]=value
            index=valueEnd
            if(tokens.getOrNull(index)?.symbol(",")==true)index++
        }
        var position=0
        val result=linkedMapOf<String,Summary>()
        defaults.forEach {(name,value)->result[name]=named[name] ?: positional.getOrNull(position++) ?: value}
        // Only positional arguments actually assigned to non-named parameters are consumed.
        val consumed=defaults.keys.count {it !in named}.coerceAtMost(positional.size)
        if(consumed!=positional.size)return null
        return result
    }

    private fun bound(root:Summary,parameters:Map<String,Summary>,validateDefaults:Boolean=false):Bound? {
        val visiting=hashSetOf<String>();val memo=hashMapOf<String,Bound>()
        fun expand(value:Summary):Bound? {
            var nodes=value.nodes.toLong();var depth=value.depth
            for((name,edge) in value.references) {
                var child=memo[name]
                if(child==null) {
                    if(visiting.size>=MAX_DEPTH || !visiting.add(name))return null
                    child=expand(parameters[name] ?: return null) ?: return null
                    visiting.remove(name);memo[name]=child
                }
                nodes+=edge.count.toLong()*child.nodes
                // Include the reference recursion itself, so a memoized scalar
                // alias tail cannot hide an over-deep original bind() call chain.
                depth=maxOf(depth,edge.depth+child.depth+1)
                if(nodes>MAX_EXPANDED_NODES || depth>MAX_DEPTH)return null
            }
            return if(nodes>MAX_EXPANDED_NODES || depth>MAX_DEPTH)null else Bound(nodes.toInt(),depth)
        }
        val result=expand(root) ?: return null
        if(validateDefaults)for(name in parameters.keys) {
            if(name !in memo && expand(Summary(0,0,mapOf(name to Edge(1,0))))==null)return null
        }
        return result
    }
}

/** Charge every admitted parse attempt, including malformed programs. Retain whole scenes only. */
internal class DesktopBasDocumentBudget {
    companion object {
        const val MAX_SOURCE_CHARS=2*1024*1024
        const val MAX_PARSE_WORK=131_072
        const val MAX_ELEMENTS=4_096
        const val MAX_TRANSITIONS=16_384
        const val MAX_COMPILED_TEXT_CHARS=2*1024*1024
    }
    private var sourceChars=0L;private var parseWork=0L
    private var elements=0L;private var transitions=0L
    private var compiledTextChars=0L
    fun reserve(estimate:DesktopBasParseBudget.Estimate):Boolean {
        if(sourceChars+estimate.sourceChars>MAX_SOURCE_CHARS || parseWork+estimate.workUnits>MAX_PARSE_WORK)return false
        sourceChars+=estimate.sourceChars;parseWork+=estimate.workUnits;return true
    }
    fun retain(item:BasDanmaku):Boolean {
        if(elements+item.program.elements.size>MAX_ELEMENTS || transitions+item.program.transitions.size>MAX_TRANSITIONS ||
            compiledTextChars+item.program.textContent.length>MAX_COMPILED_TEXT_CHARS)return false
        elements+=item.program.elements.size;transitions+=item.program.transitions.size
        compiledTextChars+=item.program.textContent.length;return true
    }
    fun retainCompiled(item:BasDanmaku):Boolean {
        val estimate=DesktopBasParseBudget.estimate(item.source) ?: return false
        return reserve(estimate) && retain(item)
    }
}
