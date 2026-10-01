"""Compile exact pinned upstream common/Skiko/Desktop source, in this task directory only."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, uuid
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2]
TOOLS=REPO.parent/"toolchain"; CACHE=TOOLS/"gradle-home/caches/modules-2/files-2.1"
JAVA=TOOLS/"jdk/jdk-21.0.12.1+1/bin/java.exe"
def jar(group,name,version):
    matches=list((CACHE/group/name/version).rglob("*.jar"));assert len(matches)==1,(group,name,version)
    return matches[0]
COMPILER=[jar("org.jetbrains.kotlin",a,"2.4.0") for a in ["kotlin-compiler-embeddable","kotlin-build-tools-api","kotlin-stdlib","kotlin-script-runtime","kotlin-daemon-embeddable"]]+[jar("org.jetbrains.kotlin","kotlin-reflect","1.6.10"),jar("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm","1.8.0"),jar("org.jetbrains","annotations","23.0.0")]
PLUGIN=jar("org.jetbrains.kotlin","kotlin-compose-compiler-plugin-embeddable","2.4.0")
def compile(name,args):
    file=HERE/(name+".args");file.write_text("\n".join('"'+str(a).replace('\\','/')+'"' for a in args),encoding="utf-8")
    result=subprocess.run([str(JAVA),"-Xmx3g","-cp",";".join(map(str,COMPILER)),"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","@"+str(file)],capture_output=True,text=True,encoding="utf-8",timeout=180)
    (HERE/(name+".log")).write_text(result.stdout+result.stderr,encoding="utf-8")
    print((result.stdout+result.stderr)[-10000:]);result.check_returncode()
def main():
    spec=importlib.util.spec_from_file_location("parser",REPO/"desktop/tools/sync-upstream.py"); parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    raw=(HERE/"miuix-source/miuix-squircle/build.gradle.kts").read_text(encoding="utf-8")
    tokens=parser.kotlin_tokens(raw); idx=[i for i,t in enumerate(tokens[:-1]) if t[0]=='fun' and tokens[i+1][0]=='generateSdfBytes'];assert len(idx)==1
    start=idx[0];end=start
    while tokens[end][0]!='{':end+=1
    depth=1
    while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
    declaration=raw[tokens[start][1]:tokens[end][2]]
    baker=HERE/"Bake.kt"
    baker.write_text('import kotlin.math.sqrt\n'+declaration+'''\nfun main(args:Array<String>) {
 val bytes=generateSdfBytes(512,0.643f,0.125f,64)
 val chunks=kotlin.io.encoding.Base64.encode(bytes).chunked(60000)
 val source=buildString {
  appendLine("// Copyright 2026, compose-miuix-ui contributors")
  appendLine("// SPDX-License-Identifier: Apache-2.0")
  appendLine("// Generated with the exact 5157 generateSdfBytes and original bake parameters.")
  appendLine("package top.yukonga.miuix.kmp.squircle.internal")
  appendLine("internal object BakedSquircleSdf {")
  appendLine("const val CONTROL:Float=0.643f; const val SIZE:Int=512; const val HALF_RANGE:Float=0.125f")
  appendLine("private val CHUNKS:Array<String> = arrayOf(")
  chunks.forEach{appendLine("\\\"$it\\\",")}
  appendLine(")")
  appendLine("val bytes:ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) { kotlin.io.encoding.Base64.decode(CHUNKS.joinToString(\\\"\\\")) }")
  appendLine("}")
 }
 java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),source)
}
''',encoding="utf-8")
    baked=HERE/"BakedSquircleSdf.kt"; bakerOutput=HERE/"baker-classes";bakerOutput.mkdir(exist_ok=True)
    compile("bake",["-no-stdlib","-no-reflect","-jvm-target","21","-cp",str(jar("org.jetbrains.kotlin","kotlin-stdlib","2.4.0")),"-d",bakerOutput,baker])
    subprocess.run([str(JAVA),"-cp",str(bakerOutput)+";"+str(jar("org.jetbrains.kotlin","kotlin-stdlib","2.4.0")),"BakeKt",str(baked)],check=True,timeout=30)
    files=[];common=[]
    for module in ["miuix-core","miuix-shader","miuix-squircle","miuix-ui","miuix-preference","miuix-blur"]:
        for sourceSet in ["commonMain","skikoMain","desktopMain"]:
            found=list((HERE/"miuix-source"/module/"src"/sourceSet).rglob("*.kt"));files.extend(found)
            if sourceSet=="commonMain":common.extend(found)
    files.append(baked);common.append(baked)
    deps=list((HERE/"deps").glob("*.jar"))+[p for p in CACHE.rglob("*.jar") if '/org.jetbrains.kotlin/' not in p.as_posix() and 'navigationevent' not in p.as_posix()]
    deps.append(jar("org.jetbrains.kotlin","kotlin-stdlib","2.4.0"))
    target=HERE/"miuix-5157-desktop.jar"
    args=["-no-stdlib","-no-reflect","-jvm-target","21","-cp",";".join(map(str,deps)),"-Xplugin="+str(PLUGIN),"-Xmulti-platform","-Xexpect-actual-classes","-Xcommon-sources="+",".join(map(str,common)),"-module-name","miuix_5157_desktop","-d",target]+files
    compile("miuix",args)
    evidence={"commit":"5157b503e86e2bfc2db61db00fff5df41326394a","sourceFiles":len(files),"compiler":"Kotlin 2.4.0 / Compose compiler 2.4.0","jvmTarget":21,"artifact":target.name,"sha256":hashlib.sha256(target.read_bytes()).hexdigest(),"bakedSdfSourceSha256":hashlib.sha256(baked.read_bytes()).hexdigest(),"sourceBodyChanges":0}
    (HERE/"miuix-build-evidence.json").write_text(json.dumps(evidence,indent=2),encoding="utf-8");print(json.dumps(evidence))
if __name__=="__main__":main()
