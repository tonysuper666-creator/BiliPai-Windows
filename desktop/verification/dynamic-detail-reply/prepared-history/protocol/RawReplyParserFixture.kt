package com.bilipai.desktop.data

import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.data.model.response.*
import kotlin.test.*

// Fixture bytes use the existing ProtoWire field writer, without a new proto schema.
private fun replyWire(vararg fields:ByteArray)=ProtoWire.message(*fields)
private fun replyEntry(key:String,value:ByteArray)=replyWire(ProtoWire.string(1,key),ProtoWire.bytes(2,value))
private fun replyInfoFixture(id:Long=700L,child:Boolean=true,location:Boolean=true):ByteArray {
    val content=replyWire(
        ProtoWire.string(1,"Hello &amp; friends"),
        ProtoWire.bytes(3,replyEntry("[smile]",replyWire(
            ProtoWire.string(2,"png"),ProtoWire.int64(5,4),ProtoWire.string(7,"gif"),
            ProtoWire.string(8,"[smile]"),ProtoWire.string(9,"webp")))),
        ProtoWire.bytes(4,replyEntry("#Topic#",byteArrayOf())),
        ProtoWire.bytes(5,replyEntry("link",replyWire(
            ProtoWire.string(1,"Title"),ProtoWire.string(3,"icon"),
            ProtoWire.string(4,"bilibili://video/BV1"),ProtoWire.string(13,"https://fixture.invalid/video")))),
        ProtoWire.bytes(6,replyWire(ProtoWire.int64(1,55),ProtoWire.string(2,"Vote"),ProtoWire.int64(3,23))),
        ProtoWire.bytes(7,replyWire(ProtoWire.string(1,"@Alice"),ProtoWire.int64(2,88))),
        ProtoWire.bytes(8,replyWire(
            ProtoWire.bytes(1,replyWire(ProtoWire.string(1,"note"),ProtoWire.string(3,"https://fixture.invalid/note"),ProtoWire.string(4,"today"))),
            ProtoWire.bytes(2,replyWire(ProtoWire.int64(1,99),ProtoWire.int64(3,900))))),
        ProtoWire.bytes(9,replyWire(
            ProtoWire.string(1,"https://fixture.invalid/p.png"),
            ProtoWire.double(2,13.0),ProtoWire.double(3,17.0),ProtoWire.double(4,1.25))))
    val member=replyWire(
        ProtoWire.int64(1,88),ProtoWire.string(2,"Member"),ProtoWire.string(4,"avatar"),
        ProtoWire.int32(5,6),ProtoWire.int32(6,0),ProtoWire.int32(7,2),ProtoWire.int32(8,1),
        ProtoWire.string(11,"pendant"),ProtoWire.string(12,"card"),ProtoWire.string(13,"focus"),
        ProtoWire.string(15,"123"),ProtoWire.string(16,"#ffffff"),ProtoWire.bool(17,true),
        ProtoWire.string(18,"medal"),ProtoWire.int32(19,7),ProtoWire.int32(32,1))
    val control=replyWire(
        ProtoWire.int32(1,2),ProtoWire.bool(3,true),ProtoWire.bool(12,true),
        ProtoWire.bytes(19,replyWire(
            ProtoWire.string(1,"Label"),ProtoWire.string(4,"#fff"),ProtoWire.string(11,"https://fixture.invalid/label"))),
        if(location)ProtoWire.string(25,"IP属地：测试") else byteArrayOf(),
        ProtoWire.int32(37,2))
    return replyWire(
        if(child)ProtoWire.bytes(1,replyInfoFixture(701,false,location))else byteArrayOf(),
        ProtoWire.int64(2,id),ProtoWire.int64(3,900),ProtoWire.int32(4,11),ProtoWire.int64(5,88),
        ProtoWire.int64(6,if(child)0 else 700),ProtoWire.int64(7,if(child)0 else 700),
        ProtoWire.int64(8,77),ProtoWire.int32(9,8),ProtoWire.int64(10,10),ProtoWire.int32(11,5),
        ProtoWire.bytes(12,content),ProtoWire.bytes(13,member),ProtoWire.bytes(14,control))
}
private fun replySubjectFixture()=replyWire(
    ProtoWire.int64(1,88),ProtoWire.bool(11,true),ProtoWire.bool(13,true),
    ProtoWire.string(14,"root hint"),ProtoWire.string(15,"child hint"),
    ProtoWire.int64(16,33),ProtoWire.int32(26,1))
internal fun rawReplyMainFixture(location:Boolean=true)=replyWire(
    ProtoWire.bytes(1,replyWire(ProtoWire.int32(1,2))),
    ProtoWire.bytes(2,replyInfoFixture(location=location)),
    ProtoWire.bytes(3,replySubjectFixture()),
    ProtoWire.bytes(4,replyInfoFixture(710,false,location)),
    ProtoWire.bytes(5,replyInfoFixture(711,false,location)),
    ProtoWire.bytes(6,replyInfoFixture(712,false,location)),
    ProtoWire.bytes(20,replyWire(ProtoWire.string(1,"grpc-next"))))
internal fun rawReplyDetailFixture()=replyWire(
    ProtoWire.bytes(2,replySubjectFixture()),ProtoWire.bytes(3,replyInfoFixture()),
    ProtoWire.bytes(8,replyWire(ProtoWire.string(1,"detail-next"))))
internal fun rawReplyDialogFixture()=replyWire(
    ProtoWire.bytes(2,replySubjectFixture()),ProtoWire.bytes(3,replyInfoFixture(701,false)),
    ProtoWire.bytes(5,replyWire(ProtoWire.string(1,"dialog-next"))))
internal fun rawReplyTranslateFixture(id:Long)=replyWire(
    ProtoWire.bytes(1,replyWire(ProtoWire.int64(1,id),
        ProtoWire.bytes(2,replyWire(ProtoWire.bytes(17,replyWire(ProtoWire.string(1,"translated &amp; text"))))))))

internal fun assertRawReplyFixture(item:ReplyItem) {
    assertEquals(700L,item.rpid);assertEquals(900L,item.oid);assertEquals(88L,item.mid)
    assertEquals(11,item.replyType);assertEquals(77L,item.dialog)
    assertEquals(8,item.like);assertEquals(10L,item.ctime);assertEquals(5,item.count);assertEquals(5,item.rcount)
    assertEquals(2,item.action);assertEquals(701L,item.replies!!.single().rpid)
    assertEquals(700L,item.replies!!.single().parent);assertEquals(700L,item.replies!!.single().root)
    val control=item.replyControl!!
    assertTrue(control.upReply);assertTrue(control.isUpTop);assertEquals(2,control.translationSwitch)
    assertEquals("IP属地：测试",control.location)
    assertEquals("Label",item.cardLabels!!.single().textContent)
    assertEquals("#fff",item.cardLabels!!.single().labelColor)
    assertEquals("https://fixture.invalid/label",item.cardLabels!!.single().jumpUrl)
    val content=item.content
    assertEquals("Hello & friends",content.message)
    assertEquals(ReplyEmote(4,"[smile]","webp"),content.emote!!.getValue("[smile]"))
    assertEquals(88L,content.atNameToMid["@Alice"]);assertTrue("#Topic#" in content.topics)
    assertEquals("https://fixture.invalid/video",content.urls.getValue("link").url)
    assertEquals("bilibili://video/BV1",content.urls.getValue("link").appUrlSchema)
    assertEquals("icon",content.urls.getValue("link").prefixIcon)
    assertEquals(ReplyVote(55,"Vote",23),content.vote)
    assertEquals("note",content.richText.note!!.summary)
    assertEquals("https://fixture.invalid/note",content.richText.note!!.clickUrl)
    assertEquals("today",content.richText.note!!.lastMtimeText)
    assertEquals(ReplyRichTextOpus(99,900),content.richText.opus)
    assertEquals(ReplyPicture("https://fixture.invalid/p.png",13,17,1.25f),content.pictures!!.single())
    val member=item.member
    assertEquals("88",member.mid);assertEquals("Member",member.uname);assertEquals("avatar",member.avatar)
    assertEquals(6,member.levelInfo.currentLevel);assertEquals(0,member.officialVerify.type)
    assertEquals(2,member.vip!!.vipType);assertEquals(1,member.vip!!.vipStatus)
    assertEquals("pendant",member.pendant!!.image)
    assertEquals("medal",member.fansDetail!!.medalName);assertEquals(7,member.fansDetail!!.level)
    assertEquals("card",member.garbCardImage);assertEquals("focus",member.garbCardImageWithFocus)
    assertEquals("123",member.garbCardNumber);assertEquals("#ffffff",member.garbCardFanColor)
    assertEquals(1,member.garbCardIsFan);assertEquals(1,member.isSeniorMember)
}

