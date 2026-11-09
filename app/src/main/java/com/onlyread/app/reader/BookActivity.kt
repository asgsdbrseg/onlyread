package com.onlyread.app.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.onlyread.app.App
import com.onlyread.app.R
import com.onlyread.app.data.BooksDb
import com.onlyread.app.data.ProgressRow
import com.onlyread.app.data.TocRow
import com.onlyread.app.epub.EpubArchive
import com.onlyread.app.epub.EpubBook
import com.onlyread.app.util.Logx
import com.onlyread.app.util.UriPath
import com.onlyread.app.util.ZipPaths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * 阅读页（M1 分页引擎）：
 * - CSS 多栏分页：body 固定高、column-width=页宽-2*M，页 i 横向偏移 i*页宽，
 *   由 Kotlin 侧触摸层接管手势（决策 #16：左 40% 上页 / 中 20% 目录 / 右 40% 下页）；
 * - 进度锚点（FR-04/FR-11）：翻页即落库（spine+页码+首元素序号+全局百分比），
 *   重开书按「元素序号优先、页码兜底」重定位；
 * - 目录（FR-06）：中 20% 呼出，来自导入时缓存的 NAV/NCX；
 * - 决策 #19：阅读常亮，无开关。
 */
class BookActivity : Activity() {

    companion object {
        private const val TAG = "BookActivity"
        const val EXTRA_BOOK_ID = "book_id"
        private const val KEY_SPINE = "spine_index"
        private const val KEY_PAGE = "page_index"
        private const val KEY_TOTAL = "total_pages"
        private const val KEY_ANCHOR = "elem_ordinal"
        private const val HOST = "reader.local"
        private const val PICK_FONT = 4201 // 字体选择请求码
        private const val M = 16 // 页内边距（CSS px）
        private const val V = 34 // 顶部预留高度（信息条），CSS px
        // 图片排版完全交给书籍自带 CSS：不再注入 display:block / margin:auto / float:none
        // 等强制居中规则（图片原先被强制块级化并水平居中，书籍 CSS 的对齐/浮动/文字环绕全部失效）。
        // 仅保留表格基础样式；图片的尺寸保护放在 doLoad 的 pagedCss 中，不属于居中规则。
        private const val BASE_HEAD =
            "<style>html,body{margin:0;padding:0;}" +
                "table{border-collapse:collapse;}td,th{padding:4px;}</style>"

        /** M2 三套主题色板：页面底色 / 正文 / 链接 / 信息条底 / 信息条字 */
        private data class Palette(val bg: String, val text: String, val link: String, val infoBg: String, val infoText: String)
        private val PALETTES = arrayOf(
            Palette("#FFFFFF", "#1A2029", "#3A6EA5", "#F2F3F5", "#1A2029"),
            Palette("#F6EEDD", "#43382A", "#7A5C2E", "#EFE3C8", "#43382A"),
            Palette("#101010", "#A9A9A9", "#7FA7CC", "#000000", "#A9A9A9")
        )

        // 分页初始化脚本（transform 翻页版）：
        // ① 所有正文移入 #__wrap；body 只是裁剪窗口（width=W,overflow:hidden）
        // ② 列几何在 wrap 上：colW=W-2M（W=分数实测视口宽），gap=W-colW → 列距=W
        // ③ 翻页= wrap.style.transform=translateX(-n*W)，彻底不依赖 scrollLeft
        //   （设备 WebView 对根滚动范围语义不一，末页会被钳制；body 自身的 transform
        //    会按规范传播到视口而不生效，故必须用内层 wrap）
        // ④ 总页数= max(wrap.scrollWidth 推算, 末可见元素右缘推算) 双信号
        private val INIT_JS = """
            window.__r={W:0,G:0,M:0,V:34,colW:0,page:0,total:1};
            window.__wrapEl=null;
            function __wrap(){if(window.__wrapEl)return window.__wrapEl;
              var w=document.createElement('div');w.id='__wrap';
              var b=document.body;var kids=[];for(var i=0;i<b.childNodes.length;i++)kids.push(b.childNodes[i]);
              for(var j=0;j<kids.length;j++)w.appendChild(kids[j]);
              b.appendChild(w);window.__wrapEl=w;return w;}
            function __els(){var w=window.__wrapEl||__wrap();
              return w.querySelectorAll("p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,td,th,div,img,svg,table,figure,figcaption,dl,dt,dd");}
            function __setupGeom(){
              var de=document.documentElement;
              var w=de.getBoundingClientRect().width;
              if(!w||w<100)w=window.innerWidth||__r.W;
              // ⚠️ 视口高度不能用 de.rect.height：html 高度会随内容增长（竖向不分栏的元凶）
              var ph=window.innerHeight||de.clientHeight;
              if(!ph||ph<100)ph=872;
              if(__r.M<=0)__r.M=16;
              if(!__r.G||__r.G<=0)__r.G=2*__r.M; // 列距=mL+mR（Kotlin 传入），兼容旧调用
              __r.W=w;__r.H=ph;
              // 非对称边距：colW=w-列距，stride=colW+列距=w，左缘偏移=__r.M
              __r.colW=Math.max(200,w-__r.G);
              __r.G=w-__r.colW;
              var b=document.body,wr=__wrap(),bs=b.style,ws=wr.style;
              bs.setProperty('width',w+'px','important');
              bs.setProperty('height',Math.ceil(ph)+'px','important');
              bs.setProperty('overflow','hidden','important');
              bs.setProperty('padding','0px','important');
              ws.setProperty('width',__r.colW+'px','important');
              ws.setProperty('column-width',__r.colW+'px','important');
              ws.setProperty('column-gap',__r.G+'px','important');
              ws.setProperty('padding-left',__r.M+'px','important');
              ws.setProperty('height',(ph-(__r.V||34)-(__r.B||0))+'px','important'); // 上下边距预留，与 Kotlin mT/mB 一致
            }
            function __lastRight(){var els=__els();
              for(var i=els.length-1;i>=0;i--){var r=els[i].getBoundingClientRect();
              if(r.width>0||r.height>0)return r.right;}return 0;}
            /** 0.4.25：归零根滚动。WebView 对同文档 # 锚点的原生导航会改
             *  documentElement.scrollLeft，与 wrap 的 transform 叠加后整屏分栏错位
             *  （两栏同时露出 / 文字被裁）；每次 __apply 都强制回到 (0,0)。 */
            function __resetScroll(){
              try{window.scrollTo(0,0);}catch(e){}
              try{var d=document.documentElement;d.scrollLeft=0;d.scrollTop=0;}catch(e){}
              try{document.body.scrollLeft=0;document.body.scrollTop=0;}catch(e){}}
            function __apply(){var el=window.__wrapEl;if(!el)return;
              el.style.setProperty('transform','translateX('+(-__r.page*__r.W)+'px)','important');
              __resetScroll();}
            function __meas(){__setupGeom();var wr=__wrap();
              wr.style.setProperty('transform','none','important');
              var t1=Math.max(1,Math.round((wr.scrollWidth+__r.M)/__r.W));
              var ext=__lastRight();
              var t2=(ext>__r.M)?Math.ceil((ext-__r.M)/__r.W):1;
              __r.total=Math.max(1,t1,t2);
              __apply();}
            function __pageOfElem(ord){var els=__els();var el=els[ord];if(!el)return -1;
              var x=el.getBoundingClientRect().left+__r.page*__r.W;
              return Math.floor((x-__r.M)/__r.W);}
            function __anchorOrd(){var lim=__r.W-__r.M-8;var els=__els();
              for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();
              if(r.width<=0||r.height<=0)continue;
              if(r.left>=-2&&r.left<lim)return i;}return -1;}
            // 0.4.23：回报当前页所属目录标题锚点（从页首元素向前找最近的目录 id），
            // 使同文件多标题时目录当前项跟随阅读位置，而不是固定取最后一条。
            function __report(){try{var o=__anchorOrd(),tf='';
              var fs=window.__tocFrags||[];
              if(fs.length&&o>=0){
                var mp={};for(var k=0;k<fs.length;k++){if(fs[k])mp[fs[k]]=1;}
                var es=__els();
                for(var i=o;i>=0;i--){var n=es[i],hit=false;
                  while(n&&n.nodeType===1){var v=n.getAttribute&&n.getAttribute('id');
                    if(v&&mp[v]){tf=v;hit=true;break;}n=n.parentNode;}
                  if(hit)break;}}
              ReaderBridge.onLayout(__r.page,__r.total,o,tf);}catch(e){}}
            /** 元素所在页（内容坐标）；被 data-fnhide 隐藏时现场解除后重测。 */
            function __pageOfNode(fe){if(!fe)return -1;
              var r=fe.getBoundingClientRect();
              if(r.width<=0&&r.height<=0){var anc=fe;
                while(anc&&anc.nodeType===1){
                  if(anc.getAttribute&&anc.getAttribute('data-fnhide')!==null){
                    anc.removeAttribute('data-fnhide');__meas();
                    r=fe.getBoundingClientRect();break;}
                  anc=anc.parentNode;}}
              var x=r.left+__r.page*__r.W;
              var pg=Math.floor((x-__r.M)/__r.W);
              if(!(pg>=0))pg=0;if(pg>__r.total-1)pg=__r.total-1;
              return pg;}
            // ————— 0.4.24：全文搜索命中词精确定位 —————
            // 旧实现把 data-srch0 加在整个命中【块】上，再用 getBoundingClientRect() 换算页码。
            // 但 CSS 多列下跨列块是 fragmented element：getBoundingClientRect() 返回所有列
            // 碎片的【并集】，left 恒取最左碎片（块起始列）。命中词在块中部/后部时，
            // 算出的页码会大幅提前；章节越长、块跨列越多越明显（>50 页时尤其突出）。
            // 且 fonts.ready 后的 __reanchor 用同一锚反复重定位，把页面持续拉回块起始列。
            // 现改为优先用命中词内联 <mark> 的首个 clientRect —— 命中词真实所在列。
            function __hitRect(fe){
              if(!fe)return null;
              var t=null;
              try{t=fe.querySelector('[data-srch-hit]');}catch(e){}
              if(!t){try{t=fe.querySelector('mark.__srch');}catch(e){}}
              if(!t&&fe.getAttribute&&fe.getAttribute('data-srch-hit')!==null)t=fe;
              // 隐藏注释命中时正文注入的是「引用块」，无命中 mark → 取引用链接所在处
              if(!t){try{t=fe.querySelector('a[href]');}catch(e){}}
              if(!t)t=fe;
              var r=null,rs=null;
              try{rs=t.getClientRects();if(rs&&rs.length)r=rs[0];}catch(e){}
              if(!r){try{r=t.getBoundingClientRect();}catch(e){}}
              // 目标仍在 data-fnhide 内（rect 全零）→ 现场解除隐藏并重测
              if(r&&r.width===0&&r.height===0){
                var anc=t;
                while(anc&&anc.nodeType===1){
                  if(anc.getAttribute&&anc.getAttribute('data-fnhide')!==null){
                    anc.removeAttribute('data-fnhide');__meas();
                    rs=null;r=null;
                    try{rs=t.getClientRects();if(rs&&rs.length)r=rs[0];}catch(e){}
                    if(!r){try{r=t.getBoundingClientRect();}catch(e){}}
                    break;}
                  anc=anc.parentNode;}}
              return r;}
            /** 命中词所在页（内容坐标）；跨列块也精确，不会被碎片并集带偏。 */
            function __pageOfHit(fe){
              var r=__hitRect(fe);if(!r)return -1;
              var x=r.left+__r.page*__r.W;
              var pg=Math.floor((x-__r.M)/__r.W);
              if(!(pg>=0))pg=0;if(pg>__r.total-1)pg=__r.total-1;
              return pg;}
            /** 按目录标题文本找首个匹配块：优先精确相等，跳过整章级大容器。 */
            function __findByText(t){var es=__els();
              var tt=String(t||'').replace(/\s+/g,'');if(!tt)return null;
              var loose=null;
              for(var i=0;i<es.length;i++){
                var s='';try{s=String(es[i].textContent||'').replace(/\s+/g,'');}catch(e){}
                if(!s||s.length>tt.length*3+24)continue;
                if(s===tt)return es[i];
                if(loose===null&&(s.indexOf(tt)>=0||(tt.indexOf(s)>=0&&s.length>=2)))loose=es[i];}
              return loose;}
            // M3.3：byUser=1 表示用户翻页（Kotlin goTo）→ 置 __touched，
            // 阻止字体就绪后的 __reanchor 重定位覆盖已发生的翻页
            window.__go=function(n,byUser){var t=__r.total;n=Math.max(0,Math.min(n,t-1));
              if(window.__initDone&&byUser)window.__touched=true;__r.page=n;
              __apply();__report();};
            function __recompute(){__meas();if(__r.page>=__r.total){__go(__r.total-1);return;}__report();}
            // M3.3：进度恢复后按锚重定位——首次 __initReader 定位发生在自定义字体
            // 加载完成前，布局漂移会让恢复位置偏差一页；fonts.ready 后重算锚页并纠正。
            // ⚠️ 修复：搜索跳转（frag/data-srch0）此前无重定位分支，字体就绪后版面变化
            // 导致落地页比正确页码【少两三页】；现补 frag 分支统一重算并纠正。
            function __reanchor(){if(!window.__initAnchor||window.__touched||!window.__initDone)return;
              __meas();var a=window.__initAnchor;
              if(a.elem!==undefined&&a.elem!==null&&a.elem>=0){
                var p2=__pageOfElem(a.elem);
                if(p2>=0&&p2!==__r.page)__go(p2);}
              else if(a.frac!==undefined&&a.frac!==null){
                var p3=Math.round(a.frac*(__r.total-1));
                if(p3>=0&&p3!==__r.page)__go(p3);}
              else if(a.frag!==undefined&&a.frag!==null){
                // 0.4.23：先按真实锚 id 命中（目录/交叉引用），避免被残留的
                // data-srch0 拽回上一次搜索命中处；仅搜索专用锚(srch0)走属性回退。
                // 0.4.24：搜索锚必须用命中词精确定位，否则跨列块会把重排后的
                // 落地页再次拉回块起始列（「比命中页提前」的根本原因之一）。
                // 0.4.27：__pageOfAnchor 兼容 name 锚 / 编码 frag / 空锚点，避免落章首
                var p4=__pageOfAnchor(a.frag);
                if(p4<0){var se=null;try{se=document.querySelector('[data-srch0]');}catch(e){}
                  if(se)p4=__pageOfHit(se);}
                if(p4>=0&&p4!==__r.page)__go(p4);}
              else if(a.text!==undefined&&a.text!==null){
                var ft=window.__tocHit||__findByText(a.text);window.__tocHit=ft;
                if(ft){var p5=__pageOfNode(ft);if(p5>=0&&p5!==__r.page)__go(p5);}}}
            window.__initReader=function(W,G,Mx,anchor,Vx,Bx){__r.W=W||0;__r.G=G||0;__r.M=Mx||0;__r.V=Vx||34;__r.B=Bx||0;__meas();
              window.__initAnchor=anchor||null;window.__touched=false;window.__initDone=false;
              var de=document.documentElement;de.scrollLeft=0;de.scrollTop=0;
              var pg=0;
              if(anchor&&anchor.elem!==undefined&&anchor.elem!==null&&anchor.elem>=0){
                var p=__pageOfElem(anchor.elem);pg=p>=0?p:Math.max(0,Math.min(anchor.fb||0,__r.total-1));}
              else if(anchor&&anchor.frac!==undefined){pg=Math.round(anchor.frac*(__r.total-1));}
              else if(anchor&&anchor.frag){
                // 同文件多标题/搜索锚：先按真实 id/name 命中，未命中才回退 data-srch0
                // 0.4.27：__pageOfAnchor 兼容 <a name="fn1">、编码 frag 与空锚点
                pg=__pageOfAnchor(anchor.frag);
                if(pg<0){var se=null;try{se=document.querySelector('[data-srch0]');}catch(e){}
                  if(se)pg=__pageOfHit(se);}
                if(pg<0)pg=0;}
              else if(anchor&&anchor.text){
                // 0.4.23：无 #frag 的同文件多标题——按标题文本定位到首个匹配块所在页
                var ft=__findByText(anchor.text);window.__tocHit=ft;
                pg=__pageOfNode(ft);if(pg<0)pg=0;}
              else if(anchor&&anchor.page!==undefined){pg=(anchor.page<0)?__r.total-1:Math.min(anchor.page,__r.total-1);}
              __go(pg);
              window.__initDone=true;
              if(document.fonts&&document.fonts.ready){try{document.fonts.ready.then(function(){setTimeout(__reanchor,80);});}catch(e){}}
              setTimeout(__reanchor,600);
              setTimeout(__reanchor,1500);
              setTimeout(function(){__recompute();},200);
              setTimeout(function(){__recompute();},900);};
            // —— M2：主题 / 排版 / 脚注 ——
            window.__setTheme=function(bg,text,link,night){
              var st=document.getElementById('__theme');
              if(!st){st=document.createElement('style');st.id='__theme';
                (document.head||document.documentElement).appendChild(st);}
              var css='html,body{background:'+bg+' !important;color:'+text+' !important;}';
              css+=night?('body p,body li,body dd,body dt,body blockquote,body td,body th,body div,body span,'
                +'body pre,body h1,body h2,body h3,body h4,body h5,body h6{color:inherit !important;}'
                +'body a{color:'+link+' !important;}')
                        :('body a{color:'+link+';}');
              st.textContent=css;
              try{document.body.style.setProperty('background',bg,'important');}catch(e){}};
            window.__setFont=function(px,lh,fam,anchor){var wr=__wrap(),ws=wr.style,de=document.documentElement,ds=de.style;
              if(px>0){ws.setProperty('font-size',px+'px','important');
                // rem 相对根字号：必须同时设 html 根字号才能影响 rem 排版
                ds.setProperty('font-size',px+'px','important');}
              else{ws.removeProperty('font-size');ds.removeProperty('font-size');}
              if(lh>0)ws.setProperty('line-height',lh,'important');else ws.removeProperty('line-height');
              if(fam)ws.setProperty('font-family',fam,'');else ws.removeProperty('font-family');
              __meas();
              var pg=(anchor!==undefined&&anchor!==null&&anchor>=0)?__pageOfElem(anchor):-1;
              if(pg<0)pg=Math.min(__r.page,__r.total-1);
              __go(pg);};
            // —— 注释类链接判定（M2.3）——
            // 只有 epub:type="noteref"（及等价写法）的链接才允许弹注；普通章节锚点、
            // 目录/交叉引用、外链一律走 WebView 原生导航（跳章），绝不弹注释层。
            // 兼容：XHTML 命名空间下的 epub:type、EPUB3 的 role="doc-noteref"、
            // EPUB2 常见的 class="noteref"；属性值归一化后整词比对，避免 noteref-xxx 误命中。
            // 兜底（M2.4）：全无标记属性的裸链接（古籍常见 <a href="#fn1">）——
            // 同文档 href 指向 aside / epub:type 含 footnote|endnote 的目标时同样视为 noteref，
            // 否则注释密集书（《莊子集釋》等）弹注整体失效。
            function __isNoteRef(a){
              if(!a||!a.getAttribute)return false;
              var v='';
              try{v=a.getAttribute('epub:type')||'';}catch(e){}
              if(!v){try{v=(a.getAttributeNS&&a.getAttributeNS('http://www.idpf.org/2007/ops','type'))||'';}catch(e){}}
              if(!v){try{v=a.getAttribute('role')||'';}catch(e){}}
              if(!v){try{v=a.getAttribute('class')||'';}catch(e){}}
              if(!v){try{v=a.getAttribute('type')||'';}catch(e){}}
              if(!v){
                var h=String(a.getAttribute('href')||'');
                if(h.charAt(0)!=='#')return false;
                // 0.4.27：统一走 __findAnchor，兼容 <a name="fn1"> 与编码 frag
                var t=__findAnchor(h.slice(1));
                if(!t)return false;
                // 0.4.26：markChapterForReader 现在只给「标准注释容器」（<aside> /
                // epub:type=footnote|note|endnote|rearnote）标 data-fnhide。带该属性即说明
                // 目标是真注释 → 就地弹层，绝不解除隐藏翻页（解除会整章重排错位）。
                // EPUB2 裸注释目标已从 0.4.26 起不再隐藏，不带此属性，故落到下面的
                // 类型判定返回 false，由 __goFrag 按内容坐标直接翻页跳转。
                var q=t;
                while(q&&q.nodeType===1){
                  if(q.getAttribute&&q.getAttribute('data-fnhide')!==null)return true;
                  q=q.parentNode;}
                var tv='',tg=t.tagName||'';
                try{tv=t.getAttribute('epub:type')||'';}catch(e){}
                if(!tv){try{tv=(t.getAttributeNS&&t.getAttributeNS('http://www.idpf.org/2007/ops','type'))||'';}catch(e){}}
                tv=String(tv).toLowerCase();
                return tv.indexOf('footnote')>=0||tv.indexOf('endnote')>=0||tg==='ASIDE';
              }
              var t=' '+String(v).toLowerCase().replace(/[^a-z0-9-]+/g,' ')+' ';
              return t.indexOf(' noteref ')>=0||t.indexOf(' doc-noteref ')>=0;
            }
            // 0.4.27：统一锚点查找。EPUB2 常用 <a name="fn1"> 而非 id，且 Uri.fragment
            // 可能是百分号编码；旧 getElementById 单一路径找不到目标时，跨章跳转的
            // Anchor.Frag 分支会 __pageOfNode(null)=-1 → pg=0，表现为「跳到章节开头」。
            // 依次尝试：原始 / 解码 / 编码 id、同名 name、属性选择器（JSON.stringify
            // 负责转义），最后由调用方回退 data-srch0（搜索专用锚）。
            function __findAnchor(id){
              if(id===undefined||id===null)return null;
              var s=String(id),ids=[s];
              try{var d=decodeURIComponent(s);if(d&&d!==s)ids.push(d);}catch(e){}
              try{var e2=encodeURIComponent(s);if(e2&&e2!==s)ids.push(e2);}catch(e){}
              for(var k=0;k<ids.length;k++){
                var v=ids[k],el=null;
                try{el=document.getElementById(v);}catch(e){}
                if(el)return el;
                try{var ns=document.getElementsByName(v);if(ns&&ns.length)return ns[0];}catch(e){}
                try{el=document.querySelector('[id='+JSON.stringify(v)+']');}catch(e){}
                if(el)return el;
                try{el=document.querySelector('[name='+JSON.stringify(v)+']');}catch(e){}
                if(el)return el;
              }
              return null;
            }
            /** 0.4.27：按锚 id/name 算页码。空锚点 <a name="fn1"></a> 单独成行时
             *  rect 为 0×0 且落在上一列末尾，直接换算会停在注释正文的前一页；
             *  此时改用紧随其后的可见块级兄弟（注释正文）定位。 */
            function __pageOfAnchor(id){
              var el=__findAnchor(id);if(!el)return -1;
              var pg=__pageOfHit(el),r=null;
              try{r=el.getBoundingClientRect();}catch(e){}
              var empty=(!r||r.width<=0);
              if(!empty){try{
                if(String(el.textContent||'').replace(/\s+/g,'')===''&&
                   (!el.children||el.children.length===0))empty=true;
              }catch(e){}}
              if(empty){
                var nx=el.nextElementSibling||el.nextSibling,guard=0;
                while(nx&&guard++<6){
                  if(nx.nodeType===1){
                    var nr=null;try{nr=nx.getBoundingClientRect();}catch(e){}
                    if(nr&&(nr.width>0||nr.height>0)){pg=__pageOfHit(nx);break;}
                  }
                  nx=nx.nextElementSibling||nx.nextSibling;
                }
              }
              return pg;
            }
            // 普通同文档锚点链接：按内容坐标换算页码翻页，不交给 WebView 原生锚点滚动
            // （横向分栏 + transform 下原生滚动会把版面滚乱）。
            window.__goFrag=function(id){
              __resetScroll();
              var el=__findAnchor(id);
              if(!el)return false;
              // 0.4.26：仍带 data-fnhide 的只可能是标准注释容器（其链接已由 __isNoteRef
              // 拦成弹注，正常不会走到这里）；万一走到也绝不解除隐藏后翻页——解除会让
              // 正文流变长、整章重新分页，叠加原生 fragment 滚动即造成分栏错位。
              // EPUB2 裸注释目标不再隐藏，直接落到下面的 __pageOfHit 按内容坐标翻页跳转。
              var a=el;
              while(a&&a.nodeType===1){
                if(a.getAttribute&&a.getAttribute('data-fnhide')!==null)return false;
                a=a.parentNode;}
              var pg=__pageOfAnchor(id);if(pg<0)return false;
              __go(pg,1);return true;
            };
            // 命中测试：优先注释标记/图片，未命中返回 none（由 Kotlin 决定翻页/呼出）
            window.__hitTest=function(x,y){
              var el=null;
              try{el=document.elementFromPoint(x,y);}catch(e){}
              while(el&&el.nodeType===1){
                var tag=el.tagName;
                if(tag==='A'){
                  var href=el.getAttribute('href')||'';
                  // 非注释类链接：一律不弹注。
                  // 0.4.27：同文档 # 锚点（EPUB2 裸注释 / 普通交叉引用）就地翻页，
                  // 不再交 WebView 原生导航——后者可能触发 loadSpine 整章重载落回章首。
                  // click 捕获监听器同样会处理（双保险，__goFrag 幂等）。
                  if(!__isNoteRef(el)){
                    if(href.charAt(0)==='#'){
                      try{window.__goFrag(href.substring(1));}catch(e){}
                      return 'jump';
                    }
                    return href?'link':'none';
                  }
                  // 跨文件 noteref：note.xhtml#frag → 由 click 捕获监听器就地弹层并阻止原生导航；
                  // 此处只返回 'fn' 抑制翻页，不再重复转发弹注
                  if(href&&href.indexOf('#')>=0&&href.charAt(0)!=='#'&&!/^(https?:|mailto:|tel:)/i.test(href)){
                    return 'fn';
                  }
                  if(href.charAt(0)==='#'){
                    var t=__findAnchor(href.substring(1));
                    if(t){var b='';try{b=t.outerHTML||t.innerHTML||'';}catch(e){}
                      try{ReaderBridge.onFootnote(String(b).slice(0,80000));}catch(e){}
                      return 'fn';}
                    return 'fn-miss';
                  }
                  // 无 # 的 noteref（目标为整篇注释文件）：同样只弹注、不跳章
                  if(href&&!/^(https?:|mailto:|tel:)/i.test(href))return 'fn';
                  return 'link';
                }
                // 0.4.25：单击图片不再进查看器（改为长按 __hitTestImg）。
                // 注释标记常写成 <a href="#fn1"><img .../></a>，必须继续向上找到 <a>
                // 才能触发注释跳转；裸图片则一路落到 none，按区域翻页。
                el=el.parentNode;
              }
              return 'none';
            };
            /** 0.4.25：长按专用——只找图片并转发大图查看，不受外层 <a> 影响。 */
            window.__hitTestImg=function(x,y){
              var el=null;try{el=document.elementFromPoint(x,y);}catch(e){}
              while(el&&el.nodeType===1){
                if(el.tagName==='IMG'){
                  var src=el.getAttribute('src')||'';if(!src)return 'img-miss';
                  var abs=src;try{abs=new URL(src,document.baseURI).href;}catch(e){}
                  try{ReaderBridge.onImage(String(abs));}catch(e){}
                  return 'img';}
                el=el.parentNode;}
              return 'none';
            };
            // 0.4.27：同文档 # 锚点在 JS 捕获阶段就地翻页并阻止原生导航。此前非注释类
            // 链接完全交给 WebView 原生导航 → 一旦 shouldOverrideUrlLoading 未识别为同文档
            // （fragment 丢失 / 路径解析成别的章）就走 loadSpine 整章重载，落地为章节开头。
            // 注释类（noteref）仍就地弹层；只有跨文件链接才放行给 shouldOverrideUrlLoading。
            document.addEventListener('click',function(e){
              // 0.4.25：长按图片打开查看器后，抬手时 WebView 仍可能补发 click ——
              // 抑制掉，避免同时触发图片所在 <a> 的注释跳转。
              if(Date.now()<(window.__imgLongPressUntil||0)){e.preventDefault();e.stopPropagation();return;}
              var a=e.target;while(a&&a.nodeType===1&&a.tagName!=='A')a=a.parentNode;
              if(!a||a.nodeType!==1)return;
              var href=a.getAttribute('href')||'';
              if(!href||/^(https?:|mailto:|tel:)/i.test(href))return;
              if(!__isNoteRef(a)){
                if(href.charAt(0)==='#'){
                  // 0.4.27：只阻止 WebView 原生导航（防止整章重载落章首 / 原生滚动乱版）。
                  // 此处【不翻页】——翻页由 __hitTest 就地 __goFrag；若在这里先翻页，
                  // 异步到达的 __hitTest 会用 elementFromPoint 命中已翻页的 DOM 而误判。
                  e.preventDefault();e.stopPropagation();
                }
                return; // 跨文件链接交 shouldOverrideUrlLoading 正常跳章
              }
              e.preventDefault();e.stopPropagation();
              if(href.charAt(0)==='#')return; // 同文档锚点：内容由 __hitTest 转发
              var abs='';try{abs=new URL(href,document.baseURI).href;}catch(err){abs=href;}
              try{ReaderBridge.onCrossRef(String(abs));}catch(err){}
            },true);
            // M3：长按选区上报（选择手柄拖动持续触发 selectionchange，防抖后回报文本+视口矩形）
            var __selT=null;
            function __selReport(){
              var s=window.getSelection&&window.getSelection();
              if(!s||s.isCollapsed||!s.rangeCount){try{ReaderBridge.onSelection('',0,0,0,0)}catch(e){}return}
              var r=s.getRangeAt(0).getBoundingClientRect();var tx=String(s.toString());
              if(tx.length>2000)tx=tx.slice(0,2000);
              try{ReaderBridge.onSelection(tx,r.left,r.top,r.right,r.bottom)}catch(e){}
            }
            document.addEventListener('selectionchange',function(){
              clearTimeout(__selT);__selT=setTimeout(__selReport,250);
            },false);
        """.trimIndent()
    }

    /** 锚点规格：恢复/跳章时的定位方式 */
    private sealed class Anchor {
        data class Page(val n: Int) : Anchor()
        data class Elem(val ord: Int, val fallbackPage: Int) : Anchor()
        data class Frac(val f: Double) : Anchor()
        data class Frag(val id: String) : Anchor()  // M2.1：同文件多标题（#fragment）跳转
        /** 0.4.23：NCX/NAV 未给 #frag 但同一 html 有多个小节标题时，按标题文本定位。 */
        data class TocText(val text: String) : Anchor()
        object First : Anchor()
        object Last : Anchor()
    }

    private val ui = Handler(Looper.getMainLooper())
    /** 统一后台线程池：onDestroy 可 shutdownNow，取代无法取消的裸 Thread。 */
    private val io: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "onlyread-reader-io").apply { isDaemon = true }
    }
    /** 页面销毁标志：后台任务、JS 回调、资源拦截据此提前退出。 */
    @Volatile
    private var destroyed = false
    private var bookId = -1L
    private var spineIndex = 0
    @Volatile
    private var archive: EpubArchive? = null
    @Volatile
    private var epub: EpubBook? = null
    @Volatile
    private var toc: List<TocRow> = emptyList()

    // 分页几何（CSS px）与状态
    private var pageW = 0
    private var pageH = 0
    private var colW = 0
    private var gap = 0
    private var curPage = 0
    private var curTotal = 1
    private var curAnchor = -1
    private var pendingAnchor: Anchor? = null
    private var lastSavedSpine = -1
    private var lastSavedPage = -1
    private var lastSavedTotal = -1
    private var gotReport = false   // 本章节是否收到过分页回报
    private var scrollMode = false  // JS 分页失败时降级为竖向滚动

    // M2：主题（全局）与排版（每书）
    private var themeId = ReaderPrefs.THEME_PAPER
    private var fontPx = 0          // 0=书内默认
    private var lineH = 0.0         // 0=书内默认
    // M3.11：资源拦截在 WebView 的 IO 线程读取，需保证可见性
    @Volatile
    private var fontFam = ""        // ''=书内 / serif / sans / custom
    // M3.10：当前书 CSS 声明的字体名（自定义字体缺字时按字符回退到它们），后台线程提取
    @Volatile
    private var bookFonts: List<String> = emptyList()
    private var mL = 16            // 左边距（CSS px，M2.2 可自定义）
    private var mR = 16            // 右边距
    private var mT = 34            // 上边距（含信息条预留）
    private var mB = 16            // 下边距
    private var lastWvW = 0
    private var lastWvH = 0
    private val relayoutPending = Runnable { relayoutPreserve() }
    private var fnWv: WebView? = null   // 弹注复用 WebView（避免每次新建导致弹注缓慢）
    private var imgOverlay: ViewGroup? = null // M2.2：图片查看全屏 overlay（decorView 顶层）
    private var fnPanel: ViewGroup? = null  // M3：Reasily 式底部浮层（scrim 根），弹注内点击标记 → 就地刷新
    private var fnPanelBox: ViewGroup? = null // 面板容器（主动定高作用对象）
    private var curHead = ""            // 当前章节书籍自带 <head>（弹注样式来源）
    private var curDir = ""             // 当前章节目录（弹注 baseURL，解析相对 CSS/图片）
    private var curSpinePath = ""       // 当前章节 zip 内路径（同文件脚注解析基准）
    private var curFnPath = ""          // 当前弹注内容来源文件（弹注内点击注释标记的解析基准）
    // 0.4.22：搜索高亮覆盖由后台线程写、加载章节的后台线程读，必须并发容器
    private val pendingSrchHtml = ConcurrentHashMap<Int, Pair<String, String>>() // (spineIdx → html/charset)
    @Volatile
    private var pendingFnHtml: String? = null // M3.3：搜索命中注释 → 章节渲染完成后自动弹注的内容

    /** 0.4.23：目录 / 阅读设置 / 全文搜索共用同一浮层（80vw×80vh 居中，跟随主题）。 */
    private var menuOverlay: ViewGroup? = null
    private var menuIsSearch = false     // 当前浮层是否搜索（关闭时需打断扫描）
    /** 0.4.23：目录当前项——最近一次目录跳转的 ord，及当前页命中的标题锚点 frag。 */
    private var activeTocOrd = -1
    private var lastTocFrag = ""
    /** 外链 CSS 改写结果缓存：key = 相对路径 + 当前前置字体栈。 */
    private val cssCache = ConcurrentHashMap<String, ByteArray>()

    private lateinit var webView: WebView
    private lateinit var infoBar: TextView
    private lateinit var bottomBar: TextView
    private lateinit var touchOverlay: View
    private var curTitle = "" // 当前章节标题（顶部信息条）
    private val bridge = Bridge() // 主阅读器与弹注弹层共用同一 JS 桥

    /** 统一后台入口：Activity 销毁后不再执行，异常留痕（不再静默吞）。 */
    private fun onIo(block: () -> Unit) {
        io.execute {
            if (destroyed) return@execute
            try {
                block()
            } catch (e: Exception) {
                Logx.w(TAG, "阅读器后台任务失败", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 决策 #19
        setContentView(R.layout.activity_book)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)

        spineIndex = savedInstanceState?.getInt(KEY_SPINE) ?: 0
        val sp = savedInstanceState?.getInt(KEY_PAGE) ?: -1
        val st = savedInstanceState?.getInt(KEY_TOTAL) ?: -1
        val sa = savedInstanceState?.getInt(KEY_ANCHOR) ?: -1
        pendingAnchor = when {
            savedInstanceState == null -> null // 将从 DB 恢复
            sa >= 0 -> Anchor.Elem(sa, sp)
            st > 1 && sp >= 0 -> Anchor.Frac(sp.toDouble() / (st - 1))
            else -> Anchor.Page(0)
        }

        webView = findViewById(R.id.webView)
        infoBar = findViewById(R.id.infoBar)
        bottomBar = findViewById(R.id.bottomBar)
        touchOverlay = findViewById(R.id.touchOverlay)
        setupWebView()
        setupTouch()

        themeId = ReaderPrefs.theme(this)
        mL = ReaderPrefs.marginL(this).coerceIn(0, 48)
        mR = ReaderPrefs.marginR(this).coerceIn(0, 48)
        mT = ReaderPrefs.marginT(this).coerceIn(0, 96)
        mB = ReaderPrefs.marginB(this).coerceIn(0, 96)
        applyThemeChrome()
        webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val nw = webView.width; val nh = webView.height
            if (nw <= 0 || nh <= 0) return@addOnLayoutChangeListener
            val changed = lastWvW > 0 && lastWvH > 0 && (nw != lastWvW || nh != lastWvH)
            lastWvW = nw; lastWvH = nh
            // 尺寸变化（旋屏 / 窗口调整等）→ 重测列距并按锚点复位；换章时 gotReport=false 自动拦截
            if (changed && gotReport && !scrollMode) {
                ui.removeCallbacks(relayoutPending)
                ui.postDelayed(relayoutPending, 250)
            }
        }

        onIo { openBook() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SPINE, spineIndex)
        outState.putInt(KEY_PAGE, curPage)
        outState.putInt(KEY_TOTAL, curTotal)
        outState.putInt(KEY_ANCHOR, curAnchor)
    }

    // ————— WebView 装配 —————

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            defaultTextEncodingName = "utf-8"
            textZoom = 100 // 固定缩放，保证分页几何稳定（M2 再做成设置）
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.addJavascriptInterface(bridge, "ReaderBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val a = pendingAnchor
                val json = when (a) {
                    is Anchor.Page -> String.format(Locale.US, "{\"page\":%d}", a.n)
                    is Anchor.Elem -> String.format(Locale.US, "{\"elem\":%d,\"fb\":%d}", a.ord, a.fallbackPage)
                    is Anchor.Frac -> String.format(Locale.US, "{\"frac\":%.4f}", a.f)
                    is Anchor.Frag -> "{\"frag\":" + org.json.JSONObject.quote(a.id) + "}"
                    is Anchor.TocText -> "{\"text\":" + org.json.JSONObject.quote(a.text) + "}"
                    is Anchor.Last -> "{\"page\":-1}"
                    else -> "{\"page\":0}"
                }
                pendingAnchor = null
                runJs("window.__initReader($pageW,$gap,$mL,$json,$mT,$mB);")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) {
                    val path = u.path ?: return true
                    val idx = spineIndexForPath(path)
                    val frag = u.fragment
                    // 0.4.27：同文档 # 锚点已由 INIT_JS 的 click 捕获阶段就地 __goFrag 翻页，
                    // 正常不会走到这里；本分支仅作兜底（JS 注入失败 / 部分 ROM 仍派发导航）。
                    // idx<0 表示路径解析失败，也先按同文档锚点尝试，避免误入 loadSpine
                    // 整章重载后停在章节开头。
                    if (frag != null && (idx == spineIndex || idx < 0)) {
                        val q = org.json.JSONObject.quote(frag)
                        ui.post { runJs("try{window.__goFrag($q)}catch(e){false}") }
                        return true
                    }
                    if (idx >= 0) {
                        // M3.9：跨章锚点改用 Anchor.Frag 定位——新章加载后 __reanchor 的 frag 分支
                        // 按内容坐标重定位到目标块所在页，不再丢到该章开头
                        loadSpine(idx, if (frag != null) Anchor.Frag(frag) else Anchor.First)
                        return true
                    }
                }
                return true // M1：外链与未知地址一律不放行
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                intercept(request.url)
        }
    }

    /** 决策 #16：左 40% 上页 / 中 20% 目录 / 右 40% 下页
     *  M3 修订：触摸层退役，改由 WebView 手势识别分流——否则长按选择（选区菜单前提）被覆盖层吃掉；
     *  单击仍走区域判定；滚动模式完全放行原生滚动 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        touchOverlay.visibility = View.GONE
        val gd = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                if (scrollMode) return false
                // 选区菜单已开：先收菜单，此次点击不翻页
                if (selPopup?.isShowing == true) { selPopup?.dismiss(); return true }
                val w = webView.width.coerceAtLeast(1)
                val zone = when {
                    e.x < w * 0.4f -> "prev"
                    e.x > w * 0.6f -> "next"
                    else -> "mid"
                }
                handleTap(e.x, e.y, zone)
                return true
            }

            override fun onLongPress(e: android.view.MotionEvent) {
                // 0.4.25：图片查看器改由长按触发；单击图片让位给 <a> 注释跳转 / 区域翻页。
                if (scrollMode || imgOverlay != null || menuOverlay != null || fnPanel != null) return
                handleLongPress(e.x, e.y)
            }
        })
        webView.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            false // 不消费：保留 WebView 原生长按选择/滚动
        }
    }

    /** 点击分流：先问 JS 命中测试（脚注/图片已在 JS 侧转发 Bridge），未命中再走翻页 */
    private fun handleTap(px: Float, py: Float, zone: String) {
        val density = resources.displayMetrics.density
        val cx = px / density
        val cy = py / density
        webView.evaluateJavascript(
            "try{window.__hitTest($cx,$cy);}catch(e){'none';}"
        ) { hit ->
            when (hit?.trim()?.removeSurrounding("'")?.removeSurrounding("\"") ?: "none") {
                "fn", "img", "jump" -> Unit  // 弹层 / 同文档锚点翻页均已由 JS 就地完成
                "link" -> Unit               // 跨文件链接：交给 WebView 原生导航跳章，不翻页
                "fn-miss" -> Toast.makeText(this, R.string.footnote_empty, Toast.LENGTH_SHORT).show()
                "img-miss" -> Toast.makeText(this, R.string.image_fail, Toast.LENGTH_SHORT).show()
                else -> when (zone) {        // none：正文按翻页处理
                    "prev" -> prevPage()
                    "next" -> nextPage()
                    else -> openToc()
                }
            }
        }
    }

    /** 0.4.25：长按命中图片 → 打开大图查看器；并抑制抬手后补发的 click。 */
    private fun handleLongPress(px: Float, py: Float) {
        val density = resources.displayMetrics.density
        val cx = px / density
        val cy = py / density
        webView.evaluateJavascript(
            "try{window.__hitTestImg($cx,$cy);}catch(e){'none';}"
        ) { hit ->
            when (hit?.trim()?.removeSurrounding("'")?.removeSurrounding("\"") ?: "none") {
                "img" -> runJs("window.__imgLongPressUntil=Date.now()+900")
                "img-miss" -> Toast.makeText(this, R.string.image_fail, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onLayout(page: Int, total: Int, anchor: Int, tocFrag: String) {
            if (destroyed) return
            ui.post {
                gotReport = true
                curPage = page
                curTotal = total.coerceAtLeast(1)
                curAnchor = anchor
                // 0.4.23：__report 回报当前页所属目录标题锚点，供目录当前项定位
                lastTocFrag = tocFrag
                if (tocFrag.isNotEmpty()) {
                    toc.firstOrNull { it.spineIndex == spineIndex && it.frag == tocFrag }
                        ?.let { activeTocOrd = it.ord }
                }
                if (!scrollMode) updateInfo()
                saveProgress()
                // M3.3：搜索命中注释 → 页面就绪后弹出注释内容（一次性）
                val fn = pendingFnHtml
                if (fn != null) {
                    pendingFnHtml = null
                    showFootnote(fn)
                }
            }
        }

        /** M2：脚注内容回报（同文档 # 锚点由命中测试转发） */
        @JavascriptInterface
        fun onFootnote(html: String) {
            if (destroyed) return
            ui.post { showFootnote(html) }
        }

        /** M2.2：跨文件脚注回报（命中测试转发，URL 已解析为 reader.local 绝对地址） */
        @JavascriptInterface
        fun onCrossRef(url: String) {
            if (destroyed) return
            ui.post { openCrossRef(url) }
        }

        /** M2：图片点击回报（URL 已解析为 reader.local 绝对地址） */
        @JavascriptInterface
        fun onImage(url: String) {
            if (destroyed) return
            ui.post { showImage(url) }
        }

        /** M2.2：弹注内点击注释标记 → 就地刷新弹注为所引注释内容（复用跨文件提取链路） */
        @JavascriptInterface
        fun onPopupRef(url: String) {
            if (destroyed) return
            ui.post { openCrossRef(url) }
        }

        /** M2.2：弹注文档高度上报（CSS px）→ 窗口定高 */
        @JavascriptInterface
        fun onFnH(cssH: String) {
            if (destroyed) return
            val h = cssH.toFloatOrNull() ?: return
            ui.post { applyFnHeight(h) }
        }

        /** M3：长按选区上报（文本 + 视口矩形 CSS px）→ 直接弹出菜单（0.4.5 已验证可显示；
         *  0.4.6 的"抬手显示"在长按选择手势下收不到 touchend（ACTION_CANCEL），已回退） */
        @JavascriptInterface
        fun onSelection(text: String, l: Float, t: Float, r: Float, b: Float) {
            if (destroyed) return
            // 弹注浮层打开期间，selectionchange 只能来自弹注 WebView（主 WebView 被遮罩覆盖）
            val fromFn = fnWv?.parent != null
            ui.post {
                if (text.isBlank()) selPopup?.dismiss()
                else showSelectionMenu(text, l, t, r, b, fromFn)
            }
        }

        /** JS 异常回显：分页初始化失败时能在信息条看到原因 */
        @JavascriptInterface
        fun onJsError(msg: String) {
            if (destroyed) return
            ui.post {
                if (!gotReport) {
                    bottomBar.text = "JS: ${msg.take(70)}"
                    bottomBar.visibility = View.VISIBLE
                }
            }
        }
    }

    // ————— 打开与恢复 —————

    private fun openBook() {
        if (bookId <= 0) {
            ui.post { finish() }
            return
        }
        val db = (application as App).db
        val record = try {
            db.getById(bookId)
        } catch (ex: Exception) {
            Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
            null
        }
        if (record == null) {
            ui.post { finish() }
            return
        }
        // M2.2：阅读设置全局化（不再每书独立），从偏好读取
        fontPx = ReaderPrefs.fontSize(this)
        lineH = ReaderPrefs.lineHeight(this)
        fontFam = ReaderPrefs.fontFamily(this)
        val parsed = try {
            val file = File(record.path)
            if (!file.isFile) null
            else EpubArchive(file).use { it.parseBook() }?.let { book -> Pair(file, book) }
        } catch (ex: Exception) {
            Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
            null
        }
        if (parsed == null) {
            ui.post {
                Toast.makeText(this, R.string.open_fail, Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        archive = EpubArchive(parsed.first)
        epub = parsed.second
        val bookObj = parsed.second
        // M3.3：优先恢复阅读——先读进度、立即加载进度章；
        // 目录入库（replaceToc 全量写库）与书架活跃时间延后，不阻塞首章渲染
        val p = try {
            db.getProgress(bookId)
        } catch (ex: Exception) {
            Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
            null
        }
        ui.post {
            // 打开大书/复杂书时先给一条加载反馈，避免 WebView 渲染前的空白等待期毫无响应
            bottomBar.visibility = View.VISIBLE
            bottomBar.text = getString(R.string.opening)
            val b = epub
            if (p != null && b != null && b.spine.isNotEmpty()) {
                val si = p.spineIndex.coerceIn(0, b.spine.size - 1)
                loadSpine(si, if (p.elemOrdinal >= 0) Anchor.Elem(p.elemOrdinal, p.pageIndex) else Anchor.Page(p.pageIndex))
            } else {
                loadSpine(0, Anchor.First)
            }
        }
        // 目录与元数据收尾（后台）：内存列表经 ui.post 赋值（目录 UI 主线程读）
        val tocRows = bookObj.toc.mapIndexed { i, t ->
            TocRow(i, t.spineIndex, t.title, t.level, t.frag ?: "")
        }
        if (tocRows.isNotEmpty()) {
            ui.post { toc = tocRows }
            onIo {
                runCatching { db.replaceToc(bookId, tocRows) }
                    .onFailure { Logx.w(TAG, "目录缓存写入失败", it) }
                runCatching { db.touch(bookId, System.currentTimeMillis()) }
                    .onFailure { Logx.w(TAG, "更新阅读时间失败", it) }
            }
        } else {
            // 本次解析无目录（罕见）：回退 DB 缓存；touch 仍延后
            val cached = try {
                db.getToc(bookId)
            } catch (ex: Exception) {
                Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                emptyList()
            }
            ui.post { toc = cached }
            onIo {
                runCatching { db.touch(bookId, System.currentTimeMillis()) }
                    .onFailure { Logx.w(TAG, "更新阅读时间失败", it) }
            }
        }
    }

    // ————— 分页加载 —————

    private fun loadSpine(index: Int, anchor: Anchor) {
        val arch = archive ?: return
        val book = epub ?: return
        // M3：EPUB3 NAV（目录文件）不作为正文渲染——任何入口（打开/目录/翻页）命中即顺延到下一章。
        // M3.5：Reasily 式——navPaths 为全部目录页集合（多 nav + 不规范书启发式识别），一并不渲染
        val navs = book.navPaths.ifEmpty { book.navPath?.let { setOf(it) } ?: emptySet() }
        if (book.spine.getOrNull(index)?.path in navs) {
            var j = index
            while (j < book.spine.size && book.spine[j].path in navs) j++
            if (j < book.spine.size && j != index) {
                loadSpine(j, Anchor.First)
                return
            }
        }
        if (index < 0 || index >= book.spine.size) return
        val item = book.spine[index]
        onIo {
            // 优先使用搜索高亮注入的章节内容（用后即弃）；否则从 zip 解压原文
            val ov = pendingSrchHtml.remove(index)
            val (rawHtml, charset) = if (ov != null) {
                Pair(ov.first, ov.second)
            } else {
                val bytes = try {
                    arch.entryBytes(item.path)
                } catch (ex: Exception) {
                    Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                    null
                }
                if (bytes == null) {
                    ui.post {
                        Toast.makeText(this@BookActivity, "章节内容缺失：${item.href}", Toast.LENGTH_SHORT).show()
                    }
                    return@onIo
                }
                val cs = ZipPaths.detectCharset(bytes)
                Pair(String(bytes, cs), cs.name())
            }
            // ⚠️ 繁重标记（多趟正则，大书/复杂书可达数百毫秒）放到后台线程：
            // 主线程只负责 geometry（廉价）+ loadDataWithBaseURL，打开/跳转不再冻结、空白期大幅缩短
            val markedHtml = ReaderHtml.markChapterForReader(rawHtml, item.path)
            // M3.12：把当前字族前置到章节内联 <style> / style= 属性的 font-family 链，
            // 与外链 CSS 的资源拦截通道同口径；custom / serif / sans 三种模式统一处理。
            val stack = fontPrependStack()
            val styledHtml = if (stack.isNotEmpty())
                ReaderHtml.prependFontFamilyInHtml(markedHtml, stack) else markedHtml
            // M3.10：书内 CSS 字体名在后台线程提取（含外链 CSS 读取），三种模式共用回退链
            val fonts = ReaderHtml.bookFontFamilies(
                rawHtml,
                if (fontFam.isNotEmpty()) ReaderFragments.linkedCssTexts(arch, item.path, rawHtml) else emptyList()
            )
            ui.post {
                bookFonts = fonts
                awaitLayout { doLoad(index, anchor, item.path, styledHtml, charset) }
            }
        }
    }

    private fun awaitLayout(action: () -> Unit) {
        if (webView.width > 0 && webView.height > 0) {
            action()
        } else {
            var tries = 0
            val r = object : Runnable {
                override fun run() {
                    if (webView.width > 0 && webView.height > 0) {
                        action()
                    } else if (tries++ < 30) {
                        ui.postDelayed(this, 50)
                    }
                }
            }
            ui.postDelayed(r, 50)
        }
    }

    private fun doLoad(index: Int, anchor: Anchor, itemPath: String, markedHtml: String, charset: String) {
        val book = epub ?: return
        if (index < 0 || index >= book.spine.size) return
        val density = resources.displayMetrics.density
        pageW = (webView.width / density).toInt().coerceAtLeast(200)
        pageH = (webView.height / density).toInt().coerceAtLeast(200)
        colW = pageW - mL - mR
        gap = mL + mR

        spineIndex = index
        curPage = 0
        curTotal = 1
        // 0.4.23：目录点击已在 openToc 设好精确 ord；这里只在「锚点来自书内链接 /
        // 进度恢复」等其它入口推断，且绝不覆盖仍属于本章的有效 ord（重复标题场景）。
        if (anchor is Anchor.Frag || anchor is Anchor.TocText) {
            val stillValid = activeTocOrd >= 0 &&
                toc.any { it.ord == activeTocOrd && it.spineIndex == index }
            if (!stillValid) {
                activeTocOrd = when (anchor) {
                    is Anchor.Frag -> toc.firstOrNull { it.spineIndex == index && it.frag == anchor.id }?.ord ?: -1
                    is Anchor.TocText -> toc.firstOrNull { it.spineIndex == index && it.title == anchor.text }?.ord ?: -1
                    else -> -1
                }
            }
        } else {
            activeTocOrd = -1
        }
        lastTocFrag = ""
        // M2.2：当前章节标题（优先目录命中，否则取文件名）
        curTitle = toc.firstOrNull { it.spineIndex == index }?.title
            ?: itemPath.substringAfterLast('/').substringBeforeLast('.').ifBlank { "正文" }
        curSpinePath = itemPath // 同文件脚注点击的解析基准（弹注内 noteref 指向本章）
        pendingAnchor = anchor
        gotReport = false
        scrollMode = false   // 每章重新尝试分页；4 秒无回报再降级

        // 关键：#__wrap 显式锁定宽度=目标列宽（column-width 只是提示值，不定宽会被拉伸导致列距漂移）
        // 几何：colW=pageW-2M，gap=2M → 列距=colW+gap=pageW；JS 会用分数精度实测值覆盖
        // body 只作裁剪窗口（width=pageW,overflow:hidden），位移由 wrap 的 transform 承担
        val h2 = pageH - mT - mB // 内容列高（上下边距内）
        val pagedCss = "<style>" +
            "html{overflow-x:hidden;}" +
            "html,body{margin:0 !important;padding:0 !important;box-sizing:content-box !important;}" +
            "body{width:${pageW}px !important;overflow:hidden !important;}" +
            "#__wrap{width:${colW}px !important;padding-left:${mL}px !important;padding-top:${mT}px !important;" +
            "column-width:${colW}px !important;column-gap:${gap}px !important;height:${h2}px !important;" +
            "column-fill:auto !important;position:relative;box-sizing:content-box !important;}" +
            // M3.11：图片宽度上限 95vw，不加 !important（书内 CSS 可覆盖）；
            // 高度保护同样不加 important，对齐/浮动/环绕完全跟随书内 CSS
            "img,svg{max-width:95vw;}body img,body svg{max-height:${h2 - 4}px;}" +
            "table{max-width:100%;}" +
            // M3.10：正文里的长网址等连续无空格串默认不在内部断行，超出列宽后被
            // body{overflow:hidden} 裁掉，还会和下一页（下一栏）文字重叠。
            // break-word 只在单词本身超过行宽时才断开，中文与常规西文排版不受影响。
            "#__wrap,#__wrap *{overflow-wrap:break-word !important;word-wrap:break-word !important;" +
            "word-break:break-word !important;}" +
            // M3：Reasily 式——正文中的注释块不进正文流（内容仍在 DOM，弹窗可取）
            "[data-fnhide]{display:none !important;}" +
            // M3.2：搜索命中注释块时展开显示（双属性选择器优先级高于单属性 fnhide）
            "[data-fnhide][data-fnshow]{display:block !important;}" +
            "</style>"
        // ⚠️ INIT_JS 必须随页面注入（此前只在 onPageFinished 里调用导致 window.__initReader 未定义，分页失效）
        // 0.4.23：本章目录锚点交给 JS，__report 由此回报「当前页属于哪个标题」
        val tocFrags = org.json.JSONArray(
            toc.filter { it.spineIndex == index && it.frag.isNotEmpty() }.map { it.frag }
        ).toString()
        val script = "<script>" + INIT_JS +
            "window.__tocFrags=" + tocFrags + ";" +
            "window.onerror=function(m,s,l){try{ReaderBridge.onJsError(String(m)+'@'+l);}catch(e){}}" +
            "</script>"
        // 备注：markedHtml 已由后台线程的 ReaderHtml.markChapterForReader 生成（含 data-fnhide 标记与保留的 data-srch0）；
        // 此处仅做廉价拼接注入，主线程不承受大正则开销。
        val full = injectHead(markedHtml, BASE_HEAD + themeCss() + typographyCss() + pagedCss + script)

        // 弹注样式来源：提取书籍自带 <head>（去脚本），注入发生在提取之后，不受污染
        curHead = Regex("(?is)<head[^>]*>(.*?)</head>").find(markedHtml)?.groupValues?.get(1)
            ?.replace(Regex("(?is)<script[\\s\\S]*?</script>"), "") ?: ""
        curDir = itemPath.substringBeforeLast('/', "")
        val base = "https://$HOST/book/$bookId/" + if (curDir.isEmpty()) "" else "$curDir/"
        webView.loadDataWithBaseURL(base, full, "text/html", charset, null)

        // 兜底：4 秒内未收到分页回报 → 降级竖向滚动模式（保证可读）
        ui.postDelayed({
            if (!gotReport && !scrollMode && !isFinishing) {
                scrollMode = true
                runJs(
                    "(function(){var w=document.getElementById('__wrap'),b=document.body;" +
                        "if(w){var s=w.style;s.setProperty('width','auto','important');" +
                        "s.setProperty('column-width','auto','important');s.setProperty('column-gap','normal','important');" +
                        "s.setProperty('height','auto','important');s.setProperty('padding-top','0px','important');" +
                        "s.setProperty('transform','none','important');}" +
                        "b.style.setProperty('overflow','auto','important');" +
                        "b.style.setProperty('height','auto','important');" +
                        "document.documentElement.style.overflowX='auto';})()"
                )
                bottomBar.text = "滚动模式（分页初始化失败）"
                bottomBar.visibility = View.VISIBLE
            }
        }, 4000)
    }

    private fun injectHead(html: String, headCss: String): String =
        if (html.contains("</head>", ignoreCase = true)) {
            html.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), headCss + "</head>")
        } else {
            headCss + html
        }

    private fun runJs(js: String) {
        if (webView.width <= 0) return
        webView.evaluateJavascript(js, null)
    }

    // ————— 翻页与章节 —————

    private fun nextPage() {
        if (scrollMode) {
            runJs("window.scrollBy(0, Math.round(document.documentElement.clientHeight*0.9));")
            return
        }
        if (curPage < curTotal - 1) {
            goTo(curPage + 1)
        } else if (epub?.let { spineIndex < it.spine.size - 1 } == true) {
            loadSpine(spineIndex + 1, Anchor.First)
        }
    }

    private fun prevPage() {
        if (scrollMode) {
            runJs("window.scrollBy(0, -Math.round(document.documentElement.clientHeight*0.9));")
            return
        }
        if (curPage > 0) {
            goTo(curPage - 1)
        } else if (spineIndex > 0) {
            loadSpine(spineIndex - 1, Anchor.Last)
        }
    }

    private fun goTo(n: Int) {
        curPage = n.coerceIn(0, curTotal - 1)
        updateInfo()
        // M3.3：不在翻页瞬间保存——此时 curAnchor 仍是上一页回报的元素序号，
        // 旧锚 + 新页码写入 DB 会导致重开书回退一页；统一由 onLayout 回报后保存。
        // byUser=1 置 __touched，阻止 JS 字体就绪后的重定位锚覆盖用户已发生的翻页
        runJs("window.__go($curPage,1);")
    }

    private fun spineIndexForPath(rawPath: String): Int {
        val b = epub ?: return -1
        val rel = ZipPaths.normalize(ZipPaths.decode(rawPath.removePrefix("/book/$bookId/")))
        return b.spine.indexOfFirst { it.path == rel }
    }

    // ————— 目录（FR-06） —————

    /** M3：返回键分层——先关图片查看，再关选区菜单，再关弹注浮层，最后才退 Activity */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menuOverlay != null) {
            closeMenuOverlay()
            return
        }
        if (imgOverlay != null) {
            closeImgOverlay()
            return
        }
        if (selPopup?.isShowing == true) {
            selPopup?.dismiss()
            return
        }
        if (fnPanel != null) {
            closeFnPanel()
            return
        }
        super.onBackPressed()
    }

    private fun openToc() {
        // M3：弹注弹层跟随主界面菜单——主菜单打开时先收起弹注（避免叠层）
        if (fnPanel != null) closeFnPanel()
        // M3：目录列表中排除 NAV（目录文件）自身条目——它不作为正文渲染。
        val navs = epub?.navPaths?.ifEmpty { epub?.navPath?.let { setOf(it) } ?: emptySet() } ?: emptySet()
        val rows = if (navs.isNotEmpty()) toc.filter { epub?.spine?.getOrNull(it.spineIndex)?.path !in navs } else toc
        if (rows.isEmpty()) {
            openSettings() // 无目录时中键直达阅读设置
            return
        }
        val d = resources.displayMetrics.density
        // M3：多级目录自动折叠——初始只展开当前章节的各级父级；点击带子级的条目展开/收起
        val expanded = HashSet<Int>()
        var filter = "" // 目录过滤关键词（非空时忽略折叠状态，只列标题命中的条目）
        fun expandAncestorsOf(target: Int) {
            var lv = rows[target].level
            for (i in target - 1 downTo 0) {
                if (rows[i].level < lv) {
                    expanded.add(i)
                    lv = rows[i].level
                    if (lv == 0) break
                }
            }
        }
        // 0.4.23：目录当前项按「当前页锚 frag → 最近跳转 ord → 该章第一条」依次回退。
        // 旧实现无条件取 spineIndex 匹配的最后一条，导致同一 html 内多个标题时
        // 无论读到哪一节，打开目录都只高亮/滚动到最后一个标题。
        val tocItems = rows.map { ReaderFragments.TocItem(it.ord, it.spineIndex, it.frag) }
        val curTocIdx = ReaderFragments.currentTocIndex(tocItems, spineIndex, lastTocFrag, activeTocOrd)
        if (curTocIdx >= 0) expandAncestorsOf(curTocIdx)
        fun hasChildren(i: Int): Boolean = i + 1 < rows.size && rows[i + 1].level > rows[i].level
        fun visibleIdx(): List<Int> {
            if (filter.isNotEmpty()) {
                return rows.indices.filter { rows[it].title.contains(filter, ignoreCase = true) }
            }
            val out = ArrayList<Int>()
            for (i in rows.indices) {
                if (rows[i].level == 0) { out.add(i); continue }
                var anc = i
                var show = true
                while (true) {
                    var parent = -1
                    for (j in anc - 1 downTo 0) {
                        if (rows[j].level < rows[anc].level) { parent = j; break }
                    }
                    if (parent < 0) break
                    if (parent !in expanded) { show = false; break }
                    anc = parent
                }
                if (show) out.add(i)
            }
            return out
        }
        val visible = ArrayList(visibleIdx())
        val listView = android.widget.ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(themeFaded(0x22))
            dividerHeight = 1
            setBackgroundColor(Color.TRANSPARENT)
            setCacheColorHint(Color.TRANSPARENT) // 滚动时不出系统默认色块
            isVerticalScrollBarEnabled = false
        }
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = visible.size
            override fun getItem(position: Int): Any = rows[visible[position]]
            override fun getItemId(position: Int): Long = visible[position].toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val idx = visible[position]
                val r = rows[idx]
                val row = convertView as? LinearLayout ?: LinearLayout(this@BookActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val title = TextView(this@BookActivity).apply {
                        id = 0x7f000001
                        textSize = 15f
                    }
                    val caret = TextView(this@BookActivity).apply {
                        id = 0x7f000002
                        textSize = 19f
                        gravity = android.view.Gravity.CENTER
                        val q = (10 * resources.displayMetrics.density).toInt()
                        setPadding(q, q, q, q)
                    }
                    addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(caret)
                }
                val tv = row.findViewById<TextView>(0x7f000001)
                val caret = row.findViewById<TextView>(0x7f000002)
                val pad = (16 + r.level * 20) * resources.displayMetrics.density.toInt()
                tv.text = r.title
                tv.setTextColor(if (idx == curTocIdx) themeLink() else themeText())
                tv.setPadding(pad, 24, 12, 24)
                if (filter.isEmpty() && hasChildren(idx)) {
                    caret.text = if (idx in expanded) "▾" else "▸"
                    caret.setTextColor(themeFaded(0x99))
                    caret.setOnClickListener {
                        if (filter.isNotEmpty()) return@setOnClickListener
                        if (idx in expanded) expanded.remove(idx) else expanded.add(idx)
                        visible.clear(); visible.addAll(visibleIdx())
                        notifyDataSetChanged()
                    }
                    caret.visibility = View.VISIBLE
                } else {
                    caret.visibility = View.INVISIBLE
                }
                return row
            }
        }
        listView.adapter = adapter
        // 过滤框置于内容顶部：浮层固定 80vw×80vh 居中且不上移，IME 从底部弹出时
        // 输入框仍在可视区内（旧实现放在底部，被键盘遮挡）。
        val filterEt = themeEditText(getString(R.string.toc_filter_hint)).apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filter = s?.toString()?.trim() ?: ""
                    visible.clear(); visible.addAll(visibleIdx())
                    adapter.notifyDataSetChanged()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val q = (10 * d).toInt()
            addView(filterEt, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(q, q, q, q) })
            addView(View(this@BookActivity).apply { setBackgroundColor(themeFaded(0x22)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        listView.setOnItemClickListener { _, _, which, _ ->
            val idx = visible.getOrNull(which) ?: return@setOnItemClickListener
            val r = rows[idx]
            activeTocOrd = r.ord
            if (r.spineIndex >= 0) {
                // 0.4.23：NCX/NAV 未给 #frag 但同一 html 有多个标题时，
                // 按标题文本定位，避免所有条目都落到章首（旧行为）。
                val a = when {
                    r.frag.isNotEmpty() -> Anchor.Frag(r.frag)
                    rows.count { it.spineIndex == r.spineIndex } > 1 -> Anchor.TocText(r.title)
                    else -> Anchor.First
                }
                loadSpine(r.spineIndex, a)
            }
            closeMenuOverlay()
        }
        showMenuOverlay(
            getString(R.string.toc_title), content,
            listOf(
                getString(R.string.settings_title) to { closeMenuOverlay(); openSettings() },
                getString(R.string.search_title) to { closeMenuOverlay(); showSearch() },
                getString(R.string.action_cancel) to { closeMenuOverlay() }
            )
        )
        // M3：目录自动定位——当前章节滚到屏幕中部
        if (curTocIdx >= 0) {
            val pos = visible.indexOf(curTocIdx)
            if (pos > 0) {
                listView.post {
                    listView.setSelectionFromTop(
                        pos, listView.height / 2 - (30 * resources.displayMetrics.density).toInt()
                    )
                }
            }
        }
    }

    // ————— M3：全书搜索（正则，按章聚合） —————

    // 块元素扫描正则在 ReaderHtml.BLOCK_PATTERN 统一定义（扫描与注入共用同一迭代序）

    private class SearchHit(
        val spineIndex: Int, val elemIndex: Int, val label: String,
        val query: String, val regexFlag: Boolean
    )

    /** M3：结果条目高亮命中词（加粗 + 链接色），正则恒启用与扫描同规则 */
    private fun highlightLabel(h: SearchHit): CharSequence {
        val re = try {
            Regex(h.query, RegexOption.IGNORE_CASE)
        } catch (ex: Exception) {
            Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
            return h.label
        }
        val sp = android.text.SpannableString(h.label)
        var n = 0
        for (m in re.findAll(h.label)) {
            if (n++ >= 8) break
            sp.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sp.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor(pal().link)),
                m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }

    // M3：搜索会话缓存——打断扫描后已得结果保留，重开弹窗可见。
    // M3.4：searchHits 整体替换（不可变快照）+ 会话令牌——过期线程的 UI 更新被丢弃，
    // 修复"本章搜索后不显示结果、重开才显示"（旧线程收尾 post 覆盖/竞态）
    private var searchHits: List<SearchHit> = emptyList()
    @Volatile
    private var searchCancelled = false
    @Volatile
    private var searchSession = 0
    private var searchPool: java.util.concurrent.ExecutorService? = null

    /** 搜索弹窗：正则（恒启用）+ 全书/当前章扫描 + Reasily 式按章聚合结果 + 点击跳转高亮 */
    private fun showSearch(prefill: String? = null) {
        if (epub == null || destroyed) return
        if (menuOverlay != null) closeMenuOverlay() // 不叠加两个菜单浮层
        if (fnPanel != null) closeFnPanel()
        val d = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0)
        }
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val et = themeEditText(getString(R.string.search_hint)).apply {
            textSize = 15f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        var scopeAll = true // M3：范围切换（全书/当前章）
        val scopeBtn = themeFlatButton("范围：全书").apply {
            textSize = 12f
            setOnClickListener {
                scopeAll = !scopeAll
                text = if (scopeAll) "范围：全书" else "范围：本章"
            }
        }
        // M3.8：异体字展开开关（持久化）——按外部 variants.json 把字面查询展开为字符类
        var variantsOn = ReaderPrefs.variantsOn(this)
        // M3.10：开关切换后立即用当前查询重新扫描；runSearch 在下方声明，用回调解耦声明顺序
        var onVariantsToggle: () -> Unit = {}
        // 启动时字表读取异常 / 回退内置表：本次打开搜索窗口提示一次
        val variantsErr = VariantsStore.consumeError()
        val variantsBtn = themeFlatButton(if (variantsOn) "异体字：开" else "异体字：关").apply {
            textSize = 12f
            setOnClickListener {
                variantsOn = !variantsOn
                ReaderPrefs.setVariantsOn(this@BookActivity, variantsOn)
                text = if (variantsOn) "异体字：开" else "异体字：关"
                onVariantsToggle()
            }
        }
        row.addView(et)
        // M3.8：第二行放「范围」与「异体字」，输入行只留 输入框+搜索
        val row2 = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        row2.addView(scopeBtn)
        row2.addView(variantsBtn)
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(themeFaded(0xBB))
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }
        val lv = android.widget.ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(themeFaded(0x22))
            dividerHeight = 1
            setBackgroundColor(Color.TRANSPARENT)
            setCacheColorHint(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        // M3：Reasily 式聚合展示——章节组头 + 命中片段行
        class G(val title: String)
        class H(val hit: SearchHit)
        var rows: List<Any> = emptyList()
        fun rebuild() {
            val out = ArrayList<Any>()
            var last = -1
            for (h in searchHits) {
                if (h.spineIndex != last) {
                    out.add(G(spineTitle(h.spineIndex)))
                    last = h.spineIndex
                }
                out.add(H(h))
            }
            rows = out
        }
        val searchAdapter: BaseAdapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getViewTypeCount(): Int = 2
            override fun getItemViewType(position: Int): Int = if (rows[position] is G) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = rows[position]
                val tv = convertView as? TextView ?: TextView(this@BookActivity)
                if (item is G) {
                    tv.text = item.title
                    tv.textSize = 14f
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    tv.setTextColor(themeLink())
                    tv.setPadding((4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
                } else {
                    tv.text = highlightLabel((item as H).hit)
                    tv.textSize = 14f
                    tv.setTypeface(null, android.graphics.Typeface.NORMAL)
                    tv.setTextColor(themeText())
                    tv.setPadding((12 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                }
                return tv
            }
        }
        lv.adapter = searchAdapter
        // 搜索状态：开始扫描后「搜索」变「取消」（停止扫描），结束/停止后复原。
        // Kotlin 局部函数需先声明，故状态与按钮引用放在 runSearch 之前。
        var searching = false
        var curCount = 0
        var searchBtn: Button? = null
        fun setSearching(b: Boolean) {
            searching = b
            searchBtn?.let { it.text = getString(if (b) R.string.action_cancel else R.string.search_btn) }
        }
        fun stopSearch() {
            if (!searching) return
            searchCancelled = true
            searchSession++ // 使在途回报过期
            searchPool?.shutdownNow()
            searchPool = null
            setSearching(false)
            status.text = getString(R.string.search_stopped, curCount)
        }
        box.addView(row)
        box.addView(row2)
        box.addView(status)
        // 0.4.23：列表用权重填满 80vh 内容区（旧版固定 430dp 在大屏留白、小屏溢出）
        box.addView(
            lv, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        fun runSearch() {
            val raw = et.text.toString().trim()
            if (raw.isEmpty()) return
            // M3.8：异体字展开——开关开启时按表把字面查询展开为字符类（如 黄钟 → [黄黃][钟锺…]）；
            // 查询含正则元字符视为用户手写正则，expandQuery 原样返回不改写
            val q = if (variantsOn) Variants.expandQuery(raw, VariantsStore.current()) else raw
            // M3.10：点「搜索」后立即显示展开状态，并贯穿进度 / 完成 / 停止各阶段
            val variantTip =
                if (variantsOn && VariantsStore.current().isNotEmpty()) " · 异体字已展开" else ""
            status.text = "搜索中…$variantTip"
            // M3：正则恒启用；无效正则直接提示
            val re = try {
                Regex(q, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                Toast.makeText(this, "无效正则：${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
            // M3.4：会话令牌——每次搜索新会话，旧线程的 UI 回报按令牌丢弃；
            // 上一线程池 shutdownNow 打断扫描（不再用裸 interrupt 单线程）
            val session = ++searchSession
            searchPool?.shutdownNow()
            searchPool = null
            searchCancelled = false
            curCount = 0
            val book = epub ?: return
            val total = book.spine.size
            val from = if (scopeAll) 0 else spineIndex.coerceIn(0, total - 1)
            val until = if (scopeAll) total else from + 1
            if (from >= until) return
            rebuild(); searchAdapter.notifyDataSetChanged() // 清空旧列表（含本次范围提示）
            val collected = java.util.Collections.synchronizedList(ArrayList<SearchHit>())
            val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
            // 纯文本查询快路径：不含正则元字符时用 lowercase indexOf（比 Regex 快数倍）
            val isPlain = !Regex("""[\\\.\[\]\{\}\(\)\*\+\?\^\$\|]""").containsMatchIn(q)
            val qLower = q.lowercase()
            fun refresh(final: Boolean) {
                val snapshot = synchronized(collected) {
                    collected.sortedWith(compareBy({ it.spineIndex }, { it.elemIndex }))
                }
                ui.post {
                    if (session != searchSession || searchCancelled) return@post // 过期会话：丢弃
                    curCount = snapshot.size
                    searchHits = snapshot
                    rebuild()
                    searchAdapter.notifyDataSetChanged()
                    status.text = if (final)
                        getString(R.string.search_done, snapshot.size) + variantTip
                    else
                        getString(R.string.search_progress, doneCount.get(), until - from, snapshot.size) + variantTip
                    if (final) setSearching(false) // 扫描自然结束 → 按钮复原「搜索」
                }
            }
            // M3.4：Reasily 式并发扫描——章节分片给固定线程池，多核并行解压+正则
            val nThreads = minOf(3, until - from)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(nThreads)
            searchPool = pool
            setSearching(true) // 扫描已启动 → 按钮切为「取消」
            val per = (until - from + nThreads - 1) / nThreads
            val active = java.util.concurrent.atomic.AtomicInteger(nThreads)
            var start = from
            while (start < until) {
                val end = minOf(start + per, until)
                val range = start until end
                start = end
                pool.submit {
                    try {
                        for (i in range) {
                            if (searchCancelled || Thread.currentThread().isInterrupted) break
                            val bytes = try {
                                archive?.entryBytes(book.spine[i].path)
                            } catch (ex: Exception) {
                                Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                                null
                            } ?: continue
                            val html = String(bytes, ZipPaths.detectCharset(bytes))
                            var ei = 0
                            for (m in ReaderHtml.BLOCK_PATTERN.findAll(html)) {
                                if (searchCancelled || Thread.currentThread().isInterrupted) break
                                val text = ReaderHtml.extractVisibleText(m.groupValues[3])
                                if (text.isEmpty()) { ei++; continue }
                                val mm: IntRange? = if (isPlain) {
                                    val idx = text.lowercase().indexOf(qLower)
                                    if (idx >= 0) IntRange(idx, idx + q.length - 1) else null
                                } else re.find(text)?.range
                                if (mm != null) {
                                    val snip = ReaderHtml.buildSnippet(text, mm)
                                    collected.add(SearchHit(i, ei, snip, q, true))
                                }
                                ei++
                            }
                            doneCount.incrementAndGet()
                            refresh(final = false) // 每章扫完立即聚合显示
                        }
                    } finally {
                        // 最后一个分片完成 → 收尾刷新（一次性）
                        if (active.decrementAndGet() == 0) refresh(final = true)
                    }
                }
            }
        }
        // M3.10：切换「异体字」开关后，用当前查询立即重新扫描（无需再点一次「搜索」）
        onVariantsToggle = {
            if (et.text.toString().trim().isNotEmpty()) runSearch()
        }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }
        val btn = themeFlatButton(getString(R.string.search_btn)).apply {
            setOnClickListener { if (searching) stopSearch() else runSearch() }
        }
        searchBtn = btn
        row.addView(btn)
        // 去掉底部「取消」按钮：关闭窗口靠点外部/返回键；停止搜索由上方按钮承担
        // —— 0.4.23：搜索与目录/设置统一为 decorView 浮层（80vw×80vh 居中）——
        // 0.4.21 用 AlertDialog + SOFT_INPUT_ADJUST_NOTHING 仍被部分 ROM 上推：
        // Dialog 是 TYPE_APPLICATION_PANEL 子窗口，系统会为保证输入框可见而平移它，
        // 忽略 ADJUST_NOTHING。改为主窗口内的普通 View 后，Activity 窗口已声明
        // adjustNothing，浮层坐标由代码写死（Gravity.CENTER），IME 弹出时
        // 窗口与面板都不发生任何位移，输入框始终在顶部可见。
        lv.setOnItemClickListener { _, _, pos, _ ->
            val item = rows.getOrNull(pos)
            if (item is H) {
                closeSearchOverlay() // 关闭浮层即打断扫描；已得结果保留在 searchHits
                jumpToSearchHit(item.hit)
            }
        }
        // 0.4.23：统一浮层（80vw×80vh 居中、跟随主题）；isSearch=true 使关闭时打断扫描
        showMenuOverlay(getString(R.string.search_title), box, isSearch = true)
        // M3.10：字表异常 / 回退内置表时提示一次，用户可据此判断关联搜索用了哪份表
        variantsErr?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        // M3：重开弹窗时先展示上次会话已得结果（按章聚合）
        if (searchHits.isNotEmpty()) {
            rebuild()
            searchAdapter.notifyDataSetChanged()
            status.text = "已有上次结果 ${searchHits.size} 条 · 输入新词可重新搜索"
        }
        // M3：选区「搜索」直达——预填并立即执行
        if (!prefill.isNullOrBlank()) {
            et.setText(prefill.trim())
            runSearch()
        }
    }

    /** 章节标题（目录优先，缺省文件名）——搜索聚合组头用 */
    private fun spineTitle(i: Int): String {
        val book = epub ?: return ""
        return toc.firstOrNull { it.spineIndex == i }?.title
            ?: book.spine.getOrNull(i)?.href?.substringAfterLast('/') ?: ""
    }

    /** 点击搜索结果：为命中块注入 data-srch0 + <mark> 高亮，Frag 锚点跳转。
     *  M3.3：命中块若在渲染时被隐藏（aside 注释内 / epub:type=note 系）：
     *  1) 定位到正文中引用该注释的标记（noteref）所在块——找不到引用者则保持原页；
     *  2) 提取注释块 HTML（命中词加 mark）→ 章节渲染完成后自动弹出注释层（showFootnote）；
     *  3) 绝不展开注释进正文流——0.4.13 的展开方案会放大版面、污染页码与进度。
     *  注入一律用 data-srch0 属性并【保留原 id】（剥 id 会破坏 markedHtml 隐藏规则）。
     *  ⚠️ lastIndexOf 判定 aside 嵌套必须省略 startIndex（默认全串向左搜）——
     *  传 startIndex=0 是 JVM「只查位置 0」语义，导致 inAside 恒 false（0.4.13 bug）。 */
    private fun jumpToSearchHit(h: SearchHit) {
        val book = epub ?: return
        val item = book.spine.getOrNull(h.spineIndex) ?: return
        val re = try {
            Regex(h.query, RegexOption.IGNORE_CASE) // M3：正则恒启用
        } catch (ex: Exception) {
            Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
            null
        }
        onIo {
            val bytes = try {
                archive?.entryBytes(item.path)
            } catch (ex: Exception) {
                Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                null
            }
            if (bytes == null) return@onIo
            val charset = ZipPaths.detectCharset(bytes)
            val html = String(bytes, charset)
            // 0.4.26：EPUB2 裸注释目标不再因「被内部 # 锚引用」而隐藏，故不再收集 refIds。
            // 只有 aside / epub:type=footnote 系标准注释容器仍隐藏并走弹注。
            val selfName = item.path.substringAfterLast('/')
            val fnTypes = hashSetOf("footnote", "note", "endnote", "rearnote")
            val etypeAttrRe = Regex("(?i)\\sepub:type\\s*=\\s*[\"']([^\"']*)[\"']")
            val idAttrRe = Regex("(?i)\\bid\\s*=\\s*[\"']?([^\"'\\s>/]+)")
            // 惰性定位目标块：仅迭代到 h.elemIndex，不为超大章节物化成千上万个 MatchResult
            val target = run {
                var res: MatchResult? = null
                var ei = 0
                val it = ReaderHtml.BLOCK_PATTERN.findAll(html).iterator()
                while (it.hasNext()) {
                    val m = it.next()
                    if (ei == h.elemIndex) { res = m; break }
                    ei++
                }
                res
            }
            val b = target
            if (b == null) {
                ui.post { loadSpine(h.spineIndex, Anchor.First) } // 结构变化兜底：跳章首
                return@onIo
            }
            fun decodeFrag(f: String): String = try {
                java.net.URLDecoder.decode(f, "UTF-8")
            } catch (ex: Exception) {
                Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                f
            }
            // 命中块隐藏判定（lastIndexOf 省略 startIndex = 全串向左搜）
            fun hiddenAt(pos: Int, attrs: String): Pair<Boolean, String?> {
                val before = html.substring(0, pos)
                val lastOpen = before.lastIndexOf("<aside", ignoreCase = true)
                val lastClose = before.lastIndexOf("</aside", ignoreCase = true)
                val inAside = lastOpen > lastClose
                val asideId = if (inAside) idAttrRe.find(before.substring(lastOpen))?.groupValues[1] else null
                val types = etypeAttrRe.find(attrs)?.groupValues[1]?.trim()?.lowercase()
                    ?.split(Regex("\\s+")) ?: emptyList()
                val ownId = idAttrRe.find(attrs)?.groupValues[1]
                val hidden = types.any { it in fnTypes } || inAside
                val targetId = ownId ?: asideId
                return Pair(hidden, targetId)
            }
            fun injected(m: MatchResult, mark: Boolean, extra: String): String {
                val inner = if (mark && re != null) ReaderFragments.markSrchText(m.groupValues[3], re) else m.groupValues[3]
                // 0.4.24：把命中词的第一个 <mark> 标为精确锚点。块上的 data-srch0 仍保留
                // （隐藏规则 / 兜底），但跨列块的 rect 是碎片并集，不能拿它算页码。
                val anchored = if (mark && re != null) ReaderFragments.anchorFirstMark(inner) else inner
                return "<" + m.groupValues[1] + m.groupValues[2] + extra + " data-srch0>" +
                    anchored + "</" + m.groupValues[1] + ">"
            }
            var out: String? = null
            val (hidden, targetId) = hiddenAt(b.range.first, b.groupValues[2])
            if (!hidden) {
                out = html.substring(0, b.range.first) + injected(b, mark = true, extra = "") +
                    html.substring(b.range.last + 1)
            } else {
                // —— 命中在隐藏注释内：定位引用者 + 弹注（不改版面） ——
                var ref: MatchResult? = null
                if (targetId != null) {
                    val hrefRe = Regex("(?i)href\\s*=\\s*[\"']([^\"']*)[\"']")
                    for (m2 in ReaderHtml.BLOCK_PATTERN.findAll(html)) {
                        // 跳过目标块本身（两次 findAll 实例不同，改用区间相等判定，等价于原 m2==b）
                        if (m2.range == b.range) continue
                        if (hiddenAt(m2.range.first, m2.groupValues[2]).first) continue
                        val has = hrefRe.findAll(m2.groupValues[3]).any { m3 ->
                            val href = m3.groupValues[1]
                            val hi = href.indexOf('#')
                            if (hi < 0 || hi + 1 >= href.length) false
                            else {
                                val file = href.substring(0, hi).substringAfterLast('/')
                                (file.isEmpty() || file.equals(selfName, ignoreCase = true)) &&
                                    decodeFrag(href.substring(hi + 1)) == targetId
                            }
                        }
                        if (has) {
                            ref = m2
                            break
                        }
                    }
                }
                // 提取注释块 HTML（aside 整块或命中块自身），命中词加 mark——弹层内高亮
                val fnHtml: String = run {
                    val before = html.substring(0, b.range.first)
                    val lastOpen = before.lastIndexOf("<aside", ignoreCase = true)
                    val lastClose = before.lastIndexOf("</aside", ignoreCase = true)
                    if (lastOpen > lastClose) {
                        Regex("(?is)<aside\\b.*?</aside\\s*>").find(html, lastOpen)?.value ?: b.value
                    } else b.value
                }
                pendingFnHtml = if (re != null) ReaderFragments.markSrchText(fnHtml, re) else fnHtml
                out = if (ref != null) {
                    // 定位到正文引用块；ref 之后的内容全部保留（含被命中的注释块）——
                    // 0.4.14 曾把注释块从 HTML 移除，导致点〔N〕标记弹出残缺注释/内容丢失
                    html.substring(0, ref.range.first) + injected(ref, mark = true, extra = "") +
                        html.substring(ref.range.last + 1)
                } else null
            }
            if (out == null) {
                // 无引用者（或无需注入）：保持原页（同章）/章首（跨章），仅弹注
                val anchor2 = if (h.spineIndex == spineIndex && curTotal > 0) Anchor.Page(curPage) else Anchor.First
                pendingSrchHtml.remove(h.spineIndex)
                ui.post { loadSpine(h.spineIndex, anchor2) }
                return@onIo
            }
            pendingSrchHtml[h.spineIndex] = Pair(out, charset.name())
            ui.post { loadSpine(h.spineIndex, Anchor.Frag("srch0")) }
        }
    }

    // 命中文本高亮已迁移至 ReaderFragments.markSrchText（纯函数）

    // 可见文本抽取（extractVisibleText）、行内标签判定（isInlineTag）、命中片段（buildSnippet）
    // 与章节注释块标记（markChapterForReader）已迁移至 ReaderHtml 纯函数对象，便于后台线程执行与单元测试。

    // ————— M2：主题 / 排版 / 脚注 / 阅读设置 —————

    private fun pal(): Palette = PALETTES[themeId.coerceIn(0, PALETTES.size - 1)]

    // ————— 0.4.23：三大菜单统一浮层（目录 / 阅读设置 / 全文搜索）—————
    // 全部挂在 Activity decorView 上：主窗口已声明 adjustNothing，浮层坐标由代码写死，
    // IME 弹出时窗口与面板都不发生位移（沿用 0.4.22 搜索窗口的「不上移」结论）。
    // 尺寸与配色统一：宽 80vw、高 80vh、Gravity.CENTER 居中；背景/文字/描边全部取 pal()。

    private fun themeText(): Int = Color.parseColor(pal().text)
    private fun themeLink(): Int = Color.parseColor(pal().link)

    /** 由主题文字色派生带 alpha 的弱化色（次要文字 / 分隔线 / 描边共用）。 */
    private fun themeFaded(alpha: Int): Int {
        val c = Color.parseColor(pal().text)
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun themePanelBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(pal().bg))
            cornerRadius = 0f
            setStroke(1, themeFaded(0x44))
        }

    /** 输入框背景：主题色淡底 + 主题色描边，避免系统 Light 主题下深色面板里出现白底黑字。 */
    private fun themeInputBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(themeFaded(0x14))
            cornerRadius = 4 * resources.displayMetrics.density
            setStroke(1, themeFaded(0x55))
        }

    /** 统一扁平按钮：主题强调色文字 + 细描边，去掉系统 Button 的浅色实心底。 */
    private fun themeFlatButton(label: CharSequence): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setSingleLine()
        setTextColor(themeLink())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 4 * resources.displayMetrics.density
            setStroke(1, themeFaded(0x55))
        }
        stateListAnimator = null
        val p = (10 * resources.displayMetrics.density).toInt()
        setPadding(p * 2, p, p * 2, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((6 * resources.displayMetrics.density).toInt(), 0, 0, 0) }
    }

    private fun themeEditText(hintText: CharSequence): android.widget.EditText =
        android.widget.EditText(this).apply {
            hint = hintText
            textSize = 14f
            setSingleLine()
            setTextColor(themeText())
            setHintTextColor(themeFaded(0x99))
            background = themeInputBg()
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

    /**
     * 显示统一浮层：80vw × 80vh、居中、跟随主题。
     * @param isSearch 传 true 时关闭浮层会打断全文搜索扫描（等价于旧 closeSearchOverlay）。
     */
    private fun showMenuOverlay(
        title: CharSequence,
        content: View,
        buttons: List<Pair<String, () -> Unit>> = emptyList(),
        isSearch: Boolean = false
    ) {
        closeMenuOverlay() // 任意时刻只存在一个菜单浮层
        val dm = resources.displayMetrics
        val d = dm.density
        val w = (dm.widthPixels * 0.80f).toInt()
        val h = (dm.heightPixels * 0.80f).toInt()
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(themeText())
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = themePanelBg()
            isClickable = true
            elevation = 8 * d
            addView(titleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@BookActivity).apply { setBackgroundColor(themeFaded(0x33)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            if (buttons.isNotEmpty()) {
                addView(View(this@BookActivity).apply { setBackgroundColor(themeFaded(0x22)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                val bar = LinearLayout(this@BookActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    val q = (8 * d).toInt()
                    setPadding(q, (6 * d).toInt(), q, (6 * d).toInt())
                }
                buttons.forEach { (label, action) ->
                    bar.addView(themeFlatButton(label).apply { setOnClickListener { action() } })
                }
                addView(bar, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeMenuOverlay() }
            addView(panel, FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER))
        }
        (window.decorView as ViewGroup).addView(
            scrim,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        menuOverlay = scrim
        menuIsSearch = isSearch
    }

    /** 关闭统一浮层：移除视图、收起输入法；搜索浮层额外打断扫描并过期在途回报。 */
    private fun closeMenuOverlay() {
        val o = menuOverlay ?: return
        menuOverlay = null
        (o.parent as? ViewGroup)?.removeView(o)
        if (menuIsSearch) {
            menuIsSearch = false
            searchCancelled = true
            searchSession++
            searchPool?.shutdownNow()
            searchPool = null
        }
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(o.windowToken, 0)
        }.onFailure { Logx.w(TAG, "收起输入法失败", it) }
    }

    /** 兼容旧调用名（搜索结果跳转、外部关闭等）。 */
    private fun closeSearchOverlay() = closeMenuOverlay()

    /** 系统栏与信息条随主题换色（窗口侧，页面侧由 #__theme 样式负责） */
    private fun applyThemeChrome() {
        val p = pal()
        // M3：主题切换时弹注/选区浮层收起，下次打开即跟随新主题配色
        if (fnPanel != null) closeFnPanel()
        if (selPopup?.isShowing == true) selPopup?.dismiss()
        val c = Color.parseColor(p.infoBg)
        infoBar.setBackgroundColor(c)
        infoBar.setTextColor(Color.parseColor(p.infoText))
        bottomBar.setBackgroundColor(c)
        bottomBar.setTextColor(Color.parseColor(p.infoText))
        window.statusBarColor = Color.parseColor(p.bg)
        window.navigationBarColor = Color.parseColor(p.bg)
        val ic = window.insetsController
        if (ic != null) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val want = if (themeId == ReaderPrefs.THEME_NIGHT) 0 else light
            ic.setSystemBarsAppearance(want, light)
        }
    }

    /** 页面主题样式（doLoad 注入，id=__theme 便于 __setTheme 热更新） */
    private fun themeCss(): String {
        val p = pal()
        val sb = StringBuilder("<style id=\"__theme\">")
        sb.append("html,body{background:").append(p.bg).append(" !important;color:").append(p.text).append(" !important;}")
        if (themeId == ReaderPrefs.THEME_NIGHT) {
            // 夜间：正文容器强制继承文字色，压制书籍自带浅底深字；链接给可读色
            sb.append("body p,body li,body dd,body dt,body blockquote,body td,body th,body div,body span,")
                .append("body pre,body h1,body h2,body h3,body h4,body h5,body h6{color:inherit !important;}")
                .append("body a{color:").append(p.link).append(" !important;}")
        } else {
            sb.append("body a{color:").append(p.link).append(";}")
        }
        // M3：搜索命中高亮（夜间换深底避免刺眼）
        if (themeId == ReaderPrefs.THEME_NIGHT) sb.append("mark.__srch{background:#6b5900 !important;color:#fff !important;}")
        else sb.append("mark.__srch{background:#ffd54f !important;color:#111 !important;}")
        sb.append("</style>")
        return sb.toString()
    }

    /** 每书排版样式（0 值=沿用书内样式，不注入）。
     *  ⚠️ html 根字号规则与 @font-face 必须位于 #__wrap 选择器块之外，
     *  嵌进块内会被当作 CSS 嵌套规则（老 WebView 甚至整条解析失败） */
    private fun typographyCss(): String {
        val htmlRule = if (fontPx > 0) "html{font-size:${fontPx}px !important;}" else ""
        val famRules = fontFamilyRules("#__wrap")
        val parts = StringBuilder()
        if (fontPx > 0) parts.append("font-size:").append(fontPx).append("px !important;")
        if (lineH > 0) parts.append("line-height:").append(lineH).append(" !important;")
        if (parts.isEmpty() && htmlRule.isEmpty() && famRules.isEmpty()) return ""
        // 只强制行距继承；⚠️ 不强制 font-size——书籍对 p/li 等的 em/百分比相对字号
        // （如 .jiao{font-size:.8em} 校注小字）必须保留，否则正文层级被拉平。
        // 字族规则由 fontFamilyRules() 统一生成（无 important 的兜底栈），与弹注同源。
        return "<style>" + fontFaceCss() + htmlRule +
            (if (parts.isNotEmpty()) "#__wrap{" + parts + "}" else "") +
            famRules +
            "#__wrap p,#__wrap li,#__wrap blockquote,#__wrap dd,#__wrap dt,#__wrap pre{" +
            "line-height:inherit !important;}</style>"
    }

    /** M2.2：自定义字体的 @font-face（经拦截通道提供字体字节） */
    private fun fontFaceCss(): String =
        if (fontFam == "custom" && hasCustomFont())
            "@font-face{font-family:'__Custom';src:url('https://$HOST/font/custom.ttf');}"
        else ""

    private fun hasCustomFont(): Boolean {
        val p = ReaderPrefs.fontPath(this)
        return p.isNotEmpty() && File(p).exists()
    }

    // 外链 CSS 字体提取已迁移至 ReaderFragments.linkedCssTexts（纯函数，正则预编译）

    /** M3.12：前置到书内 CSS `font-family` 声明前的字体栈。
     *  三种模式（custom / serif / sans）走完全相同的机制：前置栈 → 书内字体链。
     *  不含通用族（serif / sans-serif）——通用族放在链中间会截断书内回退链。
     *  返回空表示「书内」模式或自定义字体文件缺失，完全沿用书籍 CSS。
     *  M3.13：衬线前置栈补 `'Noto Serif'`（Android serif 族实际挂载的字体名），
     *  提高设备命中率；匹配不到时浏览器自动跳过，不影响后面的书内回退链。 */
    private fun fontPrependStack(): String = when (fontFam) {
        "custom" -> if (hasCustomFont()) "'__Custom'" else ""
        "serif" -> "Georgia,'Noto Serif','Noto Serif CJK SC','Source Han Serif SC'"
        "sans" -> "'Noto Sans CJK SC'"
        else -> ""
    }

    /** 根容器 / 热更新用的完整字体栈：前置栈 → 书内 CSS 字体 → 系统字体 → 链尾通用族。
     *  M3.12：三种字族模式统一由 [fontPrependStack] 与 bookFonts 组装。
     *  M3.13：链尾通用族按字族区分——衬线用 `serif`，黑体 / custom 用 `sans-serif`。
     *  通用族恒在链尾，不截断书内字体的逐字符回退；中间系统字体亦按尾族选取，
     *  否则衬线模式会被 `'Noto Sans CJK SC'` 提前命中而退化成黑体。 */
    private fun familyCss(): String {
        val head = fontPrependStack()
        if (head.isEmpty()) return ""
        val tail = if (fontFam == "serif") "serif" else "sans-serif"
        return ReaderHtml.fontStackCss(head, bookFonts, tail)
    }

    /** 字族规则单一来源：正文传 "#__wrap"，弹注传 "body"。
     *  M3.12：三种模式一致——只给根容器一条无 important 的兜底栈，不再用
     *  `!important` + 后代 `inherit` 压制书内 CSS 的字体层级。书内 CSS 的
     *  `font-family` 声明已由 prependFontFamily 前置当前字体栈，缺字时按书内
     *  原本的字体链逐字符回退。
     *  @font-face 属文档级规则，由各文档自行调用 fontFaceCss() 注入，不在此重复。 */
    private fun fontFamilyRules(selector: String): String {
        val stack = familyCss()
        return if (stack.isEmpty()) "" else selector + "{font-family:" + stack + ";}"
    }

    /** M2.2：页边距变更（上下左右独立）——重载当前章节并锚定原位置（边距参与分页 CSS，必须整页重排） */
    private fun applyMargins(l: Int, r: Int, t: Int, b: Int) {
        mL = l.coerceIn(0, 48)
        mR = r.coerceIn(0, 48)
        mT = t.coerceIn(0, 96)
        mB = b.coerceIn(0, 96)
        ReaderPrefs.setMargins(this, mL, mR, mT, mB)
        val a = when {
            curAnchor >= 0 -> Anchor.Elem(curAnchor, curPage)
            curTotal > 1 -> Anchor.Frac(curPage.toDouble() / (curTotal - 1))
            else -> Anchor.First
        }
        loadSpine(spineIndex, a)
    }

    /** M2.2：选择自定义 TTF/OTF 字体文件 */
    private fun pickFont() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, getString(R.string.pick_font_btn)), PICK_FONT)
        } catch (ex: Exception) {
            Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
            Toast.makeText(this, R.string.pick_font_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** 重排版并保持阅读位置（字号/行距/字族/窗口尺寸变化后调用） */
    private fun relayoutPreserve() {
        if (scrollMode || !gotReport) return
        val anchor = if (curAnchor >= 0) curAnchor else -1
        runJs("try{window.__setFont($fontPx,$lineH,\"${familyCss()}\",$anchor);}catch(e){}")
    }

    private fun persistTypography() {
        // M2.2：全局偏好（所有书共享同一套排版）
        ReaderPrefs.setFontSize(this, fontPx)
        ReaderPrefs.setLineHeight(this, lineH)
        ReaderPrefs.setFontFamily(this, fontFam)
    }

    /** 弹注弹层（M2.2，0.3.3 基础上修改）：复用 WebView 秒开；无标题无按钮，点外部/返回键关闭；
     *  高度由内容 wrap_content 自适应（窗口级 80% 屏高上限，超出可滚动），不做任何高度记忆；
     *  自定义字体生效；弹注内点击注释标记（noteref）就地刷新弹注 */
    private fun showFootnote(targetHtml: String) {
        showFootnoteIn(targetHtml, curHead, curDir, curSpinePath)
    }

    /** 弹注入口（含跨文件脚注：head/base 来自目标文件） */
    private fun showFootnoteIn(targetHtml: String, headCss: String, dir: String, srcPath: String) {
        if (targetHtml.isBlank()) {
            Toast.makeText(this, R.string.footnote_empty, Toast.LENGTH_SHORT).show()
            return
        }
        curFnPath = srcPath // 弹注内 noteref 相对当前内容来源文件解析
        val wv = fnWv ?: NoMenuWebView(this).also { // NoMenuWebView：弹注内长按同样屏蔽系统菜单
            it.settings.apply {
                javaScriptEnabled = true // 弹注内点击注释标记需 JS 桥
                defaultTextEncodingName = "utf-8"
                allowFileAccess = false
            }
            it.addJavascriptInterface(bridge, "ReaderBridge")
            it.setBackgroundColor(Color.parseColor(pal().bg))
            it.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    intercept(request.url) // 弹注内 CSS/图片/字体走包内资源通道

                override fun onPageFinished(view: WebView, url: String?) {
                    // 主动测高：WebView wrap_content 收缩不可靠（残留旧高度），
                    // 内容就绪后读文档实际高度上报定窗
                    view.forceLayout()
                    view.evaluateJavascript("try{__fh&&__fh()}catch(e){}", null)
                }
            }
            fnWv = it
        }
        (wv.parent as? ViewGroup)?.removeView(wv) // 复用前脱离上一次弹层的视图树
        val p = pal()
        val fnFontPx = ReaderPrefs.fnFont(this).coerceIn(10, 24)
        // 字族与正文同源：fontFamilyRules("body") 统一生成（「书内」时不注入，沿用书籍 head 样式）；
        // M3.12：三种模式均为无 important 的兜底栈，书内 CSS 的字体层级完整保留
        val popupCss = "<style>" + fontFaceCss() +
            // M3.7：弹注窗口改直角——压制书籍 CSS 可能给 html/body 设置的圆角/阴影
            "html,body{border-radius:0 !important;box-shadow:none !important;}" +
            "body{margin:0;padding:14px;background:${p.bg} !important;color:${p.text} !important;" +
            "font-size:${fnFontPx}px !important;line-height:1.65 !important;word-break:break-word;}" +
            fontFamilyRules("body") +
            // M3.11：取消图片强制居中（display:block / margin:auto / float:none 全部移除），
            // 对齐与环绕完全跟随书内 CSS；仅保留 95vw 宽度上限且不加 !important
            "img,svg{max-width:95vw;}" +
            "aside{display:block !important;}" +
            "a{color:${p.link};text-decoration:none;}" +
            "sup{font-size:.75em;}" +
            "blockquote{margin:.6em 0 .6em 1em;border-left:3px solid ${p.link};padding-left:.6em;}" +
            "</style>" +
            // 0.4.25 弹注内交互：单击图片不再进查看器（改为长按），注释标记常是 <a><img/></a>，
            // 单击须继续向上找到 <a> 才能跳转到所引注释；普通链接不再误弹一层。
            // 高度钩子：load/字体就绪/延迟重测后上报文档高度（主动定窗）
            "<script>" +
            "function __fh(){try{ReaderBridge.onFnH(String(document.documentElement.getBoundingClientRect().height))}catch(e){}}" +
            "window.addEventListener('load',__fh);" +
            "if(document.fonts&&document.fonts.ready){document.fonts.ready.then(__fh);}" +
            "setTimeout(__fh,150);setTimeout(__fh,450);" +
            // M3：弹注内选区上报——复用主界面选区菜单（坐标为弹注 WebView 内 CSS px，Java 侧加偏移）
            "var __selT=null;" +
            "function __selReport(){var s=window.getSelection&&window.getSelection();" +
            "if(!s||s.isCollapsed||!s.rangeCount){try{ReaderBridge.onSelection('',0,0,0,0)}catch(e){}return}" +
            "var r=s.getRangeAt(0).getBoundingClientRect();var tx=String(s.toString());" +
            "if(tx.length>2000)tx=tx.slice(0,2000);" +
            "try{ReaderBridge.onSelection(tx,r.left,r.top,r.right,r.bottom)}catch(e){}}" +
            "document.addEventListener('selectionchange',function(){clearTimeout(__selT);" +
            "__selT=setTimeout(__selReport,250);},false);" +
            "function __isNoteRef(a){if(!a||!a.getAttribute)return false;" +
            "var v='';try{v=a.getAttribute('epub:type')||''}catch(e){}" +
            "if(!v){try{v=(a.getAttributeNS&&a.getAttributeNS('http://www.idpf.org/2007/ops','type'))||''}catch(e){}}" +
            "if(!v){try{v=a.getAttribute('role')||''}catch(e){}}" +
            "if(!v){try{v=a.getAttribute('class')||''}catch(e){}}" +
            "if(!v){try{v=a.getAttribute('type')||''}catch(e){}}" +
            "if(!v)return false;" +
            "var t=' '+String(v).toLowerCase().replace(/[^a-z0-9-]+/g,' ')+' ';" +
            "return t.indexOf(' noteref ')>=0||t.indexOf(' doc-noteref ')>=0;}" +
            // 长按计时：500ms 内不移动才认定为看图，抬起/移动/取消均清除；
            // 命中后 __lpFired 抑制随后补发的 click，避免同时跳注释。
            "var __lpT=null,__lpFired=false,__lpX=0,__lpY=0;" +
            "function __lpClear(){if(__lpT){clearTimeout(__lpT);__lpT=null;}}" +
            "document.addEventListener('touchstart',function(e){" +
            "if(e.touches.length!==1)return;__lpFired=false;" +
            "__lpX=e.touches[0].clientX;__lpY=e.touches[0].clientY;__lpClear();" +
            "__lpT=setTimeout(function(){__lpT=null;" +
            "var el=document.elementFromPoint(__lpX,__lpY);" +
            "while(el&&el.nodeType===1&&el.tagName!=='IMG')el=el.parentNode;" +
            "if(el&&el.tagName==='IMG'){__lpFired=true;" +
            "var src=el.getAttribute('src')||'';if(!src)return;" +
            "var iu=src;try{iu=new URL(src,document.baseURI).href}catch(x){}" +
            "try{ReaderBridge.onImage(String(iu))}catch(x){}}},500);},true);" +
            "document.addEventListener('touchmove',function(e){if(e.touches.length===1&&" +
            "(Math.abs(e.touches[0].clientX-__lpX)>12||Math.abs(e.touches[0].clientY-__lpY)>12))__lpClear();},true);" +
            "document.addEventListener('touchend',function(){__lpClear();},true);" +
            "document.addEventListener('touchcancel',function(){__lpClear();__lpFired=false;},true);" +
            "document.addEventListener('click',function(e){" +
            "if(__lpFired){__lpFired=false;e.preventDefault();e.stopPropagation();return;}" +
            "var a=e.target;" +
            "while(a&&a.nodeType===1&&a.tagName!=='A')a=a.parentNode;" +
            "if(!a||a.nodeType!==1)return;" +
            "if(!__isNoteRef(a))return;" +
            "var href=a.getAttribute('href')||'';" +
            "if(!href)return;if(/^(mailto:|tel:)/i.test(href))return;" +
            "e.preventDefault();e.stopPropagation();" +
            "var abs=href;try{abs=new URL(href,document.baseURI).href}catch(x){}" +
            "try{ReaderBridge.onPopupRef(String(abs))}catch(x){}},true);</script>"
        // base 含完整文件路径：弹注内 #fragment 才能解析回来源文件（openCrossRef 依赖）
        val base = "https://$HOST/book/$bookId/" + (if (dir.isEmpty()) "" else "$dir/") +
            srcPath.substringAfterLast('/')
        val doc = "<html><head><meta charset=\"utf-8\">" + headCss + popupCss + "</head><body>" +
            targetHtml + "</body></html>"
        wv.loadDataWithBaseURL(base, doc, "text/html", "utf-8", null)
        // WebView 高度=内容实高（WRAP_CONTENT），ScrollView/窗口随之收缩；
        // 重新赋 params 触发完整重布局，避免上一弹注的尺寸残留（旧“最大高度”假象根因）
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val sc = ScrollView(this)
        sc.isVerticalScrollBarEnabled = false // M3：滚动条隐藏但不影响滚动
        sc.addView(wv)
        // M2.2/M3：Reasily 式中央弹窗——无标题栏无 ✕（遮罩点击/返回键关闭），
        // 高度=min(内容高, 80% 屏高)：内容不超高时无滚动条，超出才滚动；
        // 弹注内点击标记时同一浮层就地刷新，不堆叠
        val fb = fnPanelBox
        if (fnPanel != null && fb != null) {
            // 就地刷新：仅替换内容区
            if (fb.childCount > 0) fb.removeViewAt(0)
            fb.addView(
                sc, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fb.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT // 重置高度等重测
        } else {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = panelBg() // 四角圆角面板
                addView(
                    sc, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            val scrim = FrameLayout(this).apply {
                setBackgroundColor(0x66000000)
                setOnClickListener { closeFnPanel() } // 点遮罩关闭
                addView(
                    box, FrameLayout.LayoutParams(
                        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER // M2.2：Reasily 式屏幕中央弹窗
                    )
                )
            }
            fnPanel = scrim
            fnPanelBox = box
            (window.decorView as ViewGroup).addView(
                scrim, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(120).start() // Reasily 式淡入
        }
    }

    /** 关闭弹注浮层：脱离视图树但保留 WebView 供下次复用 */
    private fun closeFnPanel() {
        val p = fnPanel ?: return
        selPopup?.dismiss() // M3：弹注选区菜单随浮层关闭
        (p.parent as? ViewGroup)?.removeView(p)
        (fnWv?.parent as? ViewGroup)?.removeView(fnWv) // 复用前脱离
        fnPanel = null
        fnPanelBox = null
    }

    /** 弹注面板背景：M3.7 起改直角（居中弹窗、面板色随主题） */
    private fun panelBg(): android.graphics.drawable.GradientDrawable {
        val gd = android.graphics.drawable.GradientDrawable()
        gd.setColor(Color.parseColor(pal().bg))
        gd.cornerRadius = 0f
        return gd
    }

    private fun Float.dip(): Float = this * resources.displayMetrics.density

    /** 弹注浮层主动定高：min(内容高, 80% 屏高)——内容不超高时不出现滚动条 */
    private fun applyFnHeight(cssH: Float) {
        val box = fnPanelBox ?: return
        val cap = (resources.displayMetrics.heightPixels * 0.80f).toInt()
        val px = ((cssH * resources.displayMetrics.density).toInt() + 2).coerceAtMost(cap)
        if (box.layoutParams.height != px) {
            box.layoutParams.height = px
            box.requestLayout()
        }
    }

    // ————— M3：选区菜单（Reasily 式：上动作行 + 下应用图标行） —————

    private var selPopup: android.widget.PopupWindow? = null

    /** M3：当前选区文本——拖动手柄扩展选区时 onSelection 会再进来，闭包引用此字段
     *  保证复制/分享/搜索/应用拿到的是最新选区（此前旧值导致"少一个字"） */
    private var selCurText = ""

    /** 选区菜单：复制 / 分享 / 搜索（发到全文搜索）+ ColorDict 与通用文字接口应用图标。
     *  0.5.0：完全退回 0.4.5 行为——每次 selectionchange 直接 dismiss+show 重建，
     *  定位下方优先（选区底 + 12dp），放不下移上方，再放不下贴屏底 */
    private fun showSelectionMenu(text: String, l: Float, t: Float, r: Float, b: Float, fromFn: Boolean = false) {
        selPopup?.dismiss()
        if (text.isBlank()) return
        selCurText = text
        val d = resources.displayMetrics.density
        val lineColor = Color.parseColor(pal().infoText).let { Color.argb(0x30, Color.red(it), Color.green(it), Color.blue(it)) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun divider() {
                addView(View(this@BookActivity).apply {
                    setBackgroundColor(lineColor)
                }, LinearLayout.LayoutParams(1, (38 * d).toInt())) // 固定高度，避免 WRAP 行内被拉伸
            }
            fun action(labelRes: Int, onClick: () -> Unit) {
                addView(TextView(this@BookActivity).apply {
                    setText(labelRes) // 注意：参数 text 遮蔽了 TextView.text 属性，须用 setText
                    textSize = 14f
                    setTextColor(Color.parseColor(pal().text))
                    gravity = android.view.Gravity.CENTER
                    // ⚠️ 高度 WRAP+padding 决定行高；MATCH_PARENT/weight 会在 WRAP 弹窗里撑满整屏
                    setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
                    setOnClickListener { onClick() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            action(R.string.sel_copy) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(null, selCurText))
                Toast.makeText(this@BookActivity, R.string.sel_copied, Toast.LENGTH_SHORT).show()
                selPopup?.dismiss()
                clearSelection()
            }
            divider()
            action(R.string.sel_share) {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, selCurText)
                }
                runCatching { startActivity(Intent.createChooser(i, getString(R.string.sel_share))) }
                    .onFailure { Logx.w(TAG, "分享失败", it) }
                selPopup?.dismiss()
                clearSelection()
            }
            divider()
            action(R.string.sel_search) {
                selPopup?.dismiss()
                clearSelection() // 跳回正文后无残留选区高亮
                showSearch(selCurText) // 发送到全文搜索
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = selPanelBg()
            addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val apps = queryTextApps(text)
            if (apps.isNotEmpty()) {
                addView(View(this@BookActivity).apply {
                    setBackgroundColor(lineColor)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                val hscroll = android.widget.HorizontalScrollView(this@BookActivity).apply {
                    isHorizontalScrollBarEnabled = false
                }
                val row = LinearLayout(this@BookActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
                }
                for ((ai, intent) in apps) {
                    row.addView(ImageView(this@BookActivity).apply {
                        setImageDrawable(ai.loadIcon(packageManager))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).also {
                            it.marginEnd = (14 * d).toInt()
                        }
                        setOnClickListener {
                            try { startActivity(intent) } catch (ex: Exception) {
                                Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                                Toast.makeText(this@BookActivity, "无法启动应用", Toast.LENGTH_SHORT).show()
                            }
                            selPopup?.dismiss()
                            clearSelection()
                        }
                    })
                }
                hscroll.addView(row)
                addView(hscroll, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val pw = android.widget.PopupWindow(
            panel, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pw.setOnDismissListener { if (selPopup === pw) selPopup = null }
        // M3 定位（0.4.5 规则）：先按需钳宽→重测→再定位。
        // 下方优先（选区底 + 50dp，偏移加大以露出选区与拖动手柄，不紧贴文字），
        // 放不下移上方（选区顶 - 12dp - 菜单高），仍放不下贴屏底；
        // 弹注内选区坐标需加弹注 WebView 的窗口偏移
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val maxW = (screenW * 0.70f).toInt()
        if (panel.measuredWidth > maxW) {
            pw.width = maxW
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED
            )
        }
        val mw = panel.measuredWidth
        val mh = panel.measuredHeight
        var ox = 0
        var oy = 0
        if (fromFn) {
            fnWv?.let { val arr = IntArray(2); it.getLocationInWindow(arr); ox = arr[0]; oy = arr[1] }
        } // M3：PopupWindow 坐标系=窗口（非屏幕），必须用 InWindow 否则差状态栏高度
        val selL = (l * d).toInt() + ox
        val selT = (t * d).toInt() + oy
        val selB = (b * d).toInt() + oy
        var x = selL - (6 * d).toInt()
        x = x.coerceIn((8 * d).toInt(), (screenW - mw - (8 * d).toInt()).coerceAtLeast((8 * d).toInt()))
        // M3.8：默认置于选区上方（选区顶 - 菜单高 - 12dp），不遮选区与拖动手柄
        var y = selT - mh - (12 * d).toInt()
        if (y < (8 * d).toInt()) {
            y = selB + (50 * d).toInt() // 上方放不下：移到下方
        }
        if (y + mh > screenH) {
            y = screenH - mh - (16 * d).toInt() // 上下都放不下：贴屏底
        }
        pw.showAtLocation(window.decorView, android.view.Gravity.NO_GRAVITY, x.coerceAtLeast(0), y.coerceAtLeast(0))
        selPopup = pw
    }

    /** 选区菜单面板背景：圆角、主题色 */
    private fun selPanelBg(): android.graphics.drawable.GradientDrawable {
        val gd = android.graphics.drawable.GradientDrawable()
        gd.setColor(Color.parseColor(pal().infoBg))
        gd.cornerRadius = 12f * resources.displayMetrics.density
        return gd
    }

    /** 清除选区：主 WebView 与弹注 WebView 都清（动作完成后无残留高亮） */
    private fun clearSelection() {
        runJs("try{window.getSelection().removeAllRanges()}catch(e){}")
        val fw = fnWv
        if (fw != null && fw.parent != null) {
            fw.evaluateJavascript("try{window.getSelection().removeAllRanges()}catch(e){}", null)
        }
    }

    /** 枚举可接收文本的应用：ColorDict 词典接口优先，其次通用文字接口（PROCESS_TEXT / SEND） */
    private fun queryTextApps(text: String): List<Pair<android.content.pm.ResolveInfo, Intent>> {
        val out = ArrayList<Pair<android.content.pm.ResolveInfo, Intent>>()
        val seen = HashSet<String>()
        val pm = packageManager
        fun add(action: String, withType: Boolean, build: (String) -> Intent) {
            if (out.size >= 12) return
            val probe = Intent(action)
            if (withType) probe.type = "text/plain"
            val list = try { pm.queryIntentActivities(probe, 0) } catch (ex: Exception) {
                Logx.w(TAG, "查询文字处理应用失败", ex)
                emptyList()
            }
            for (ai in list) {
                val pkg = ai.activityInfo?.packageName ?: continue
                if (!seen.add(pkg)) continue
                out.add(ai to build(pkg))
                if (out.size >= 12) break
            }
        }
        add("colordict.intent.action.SEARCH", false) { pkg ->
            Intent("colordict.intent.action.SEARCH").apply {
                setPackage(pkg) // 图标直达：指定包名避免系统再弹应用选择框
                putExtra("EXTRA_QUERY", text)
                putExtra(Intent.EXTRA_TEXT, selCurText)
            }
        }
        add(Intent.ACTION_PROCESS_TEXT, true) { pkg ->
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                setPackage(pkg) // 图标直达
                putExtra(Intent.EXTRA_PROCESS_TEXT, selCurText)
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            }
        }
        add(Intent.ACTION_SEND, true) { pkg ->
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, selCurText)
                setPackage(pkg)
            }
        }
        return out
    }

    /** 跨文件注释：只弹出注释层，绝不切换当前阅读章节。
     *  目标文件不必在 spine 中（EPUB 常把注释放在独立的 notes.xhtml），直接从包内读取；
     *  无锚点时弹出该文件正文，锚点提取失败时给出提示——任何分支都不再 loadSpine。 */
    private fun openCrossRef(url: String) {
        val rel = url.substringAfter("book/$bookId/", "")
        if (rel.isEmpty()) return
        val path = ZipPaths.normalize(ZipPaths.decode(rel.substringBefore('#')))
        val frag = rel.substringAfter('#', "")
        if (path.isEmpty()) return
        onIo {
            val bytes = try {
                archive?.entryBytes(path)
            } catch (ex: Exception) {
                Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                null
            }
            if (bytes == null) {
                ui.post { Toast.makeText(this@BookActivity, R.string.footnote_empty, Toast.LENGTH_SHORT).show() }
                return@onIo
            }
            val h = String(bytes, ZipPaths.detectCharset(bytes))
            val head = Regex("(?is)<head[^>]*>(.*?)</head>").find(h)?.groupValues?.get(1)
                ?.replace(Regex("(?is)<script[\\s\\S]*?</script>"), "") ?: ""
            val dir = path.substringBeforeLast('/', "")
            val content = if (frag.isEmpty()) {
                ReaderFragments.extractBodyHtml(h)
            } else {
                // 锚点可能经 URL 编码（中文 id 等），原样取不到时按解码值重试一次
                ReaderFragments.extractFragHtml(h, frag) ?: run {
                    val decoded = try {
                        java.net.URLDecoder.decode(frag, "UTF-8")
                    } catch (ex: Exception) {
                        Logx.w(TAG, "操作失败，按回退逻辑继续", ex)
                        frag
                    }
                    if (decoded != frag) ReaderFragments.extractFragHtml(h, decoded) else null
                }
            }
            ui.post {
                if (!content.isNullOrBlank()) showFootnoteIn(content, head, dir, path)
                else Toast.makeText(this@BookActivity, R.string.footnote_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 弹注 HTML 提取已迁移至 ReaderFragments（extractBodyHtml / extractFragHtml）

    /** M3：图片查看器（Reasily 式）——全屏黑底 WebView 直读原图：
     *  WebView 内部按瓦片解码渲染，超大扫描图不占整图内存、放大不糊；
     *  内建捏合缩放/双击放大/拖动平移，返回键关闭 */
    private fun showImage(url: String) {
        val safeUrl = url.replace("\"", "%22")
        val bg = pal().bg // M2.2：背景随主题（日间浅底/夜间深底），图片上下左右居中
        val html = "<html><head><meta charset=\"utf-8\">" +
            // M3：明示缩放范围——无 minimum-scale 时部分机型捏合缩小被钳制
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1," +
            "minimum-scale=0.2,maximum-scale=8,user-scalable=yes\">" +
            "<style>html,body{margin:0;padding:0;height:100%;background:$bg}" +
            "body{display:flex;align-items:center;justify-content:center}" +
            "img{max-width:100%;max-height:100%;width:auto;height:auto}" +
            "</style></head><body><img src=\"$safeUrl\"></body></html>"
        val wv = NoMenuWebView(this)
        wv.settings.apply {
            javaScriptEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            defaultTextEncodingName = "utf-8"
        }
        wv.setBackgroundColor(Color.parseColor(pal().bg))
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                intercept(request.url) // 图片字节经包内通道直出，无需应用侧解码
        }
        // M2.2 修订：AlertDialog 内容面板是 wrap_content，窗口虽 MATCH 面板仍不全屏（WebView 高 0 → 只有遮罩）。
        // 改用 decorView 全屏 overlay（与弹注浮层同方案，已验证可靠）
        closeImgOverlay()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(pal().bg))
            addView(wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // M3：无 ✕ 按钮——点图片外的空白区域关闭（图片 WebView 消费自身触摸）
            setOnClickListener { closeImgOverlay() }
        }
        imgOverlay = overlay
        (window.decorView as ViewGroup).addView(
            overlay, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(120).start() // Reasily 式淡入
        wv.loadDataWithBaseURL(url.substringBeforeLast('/') + "/", html, "text/html", "utf-8", null)
    }

    /** 关闭图片查看 overlay 并销毁其 WebView */
    private fun closeImgOverlay() {
        val o = imgOverlay ?: return
        (o.parent as? ViewGroup)?.removeView(o)
        for (i in 0 until o.childCount) {
            (o.getChildAt(i) as? WebView)?.let { runCatching { it.destroy() } }
        }
        imgOverlay = null
    }

    /** 阅读设置面板：主题/字号/行距/字族即时生效，排版参数按书持久化 */
    private fun openSettings() {
        // M3：弹注弹层跟随主界面菜单——主菜单打开时先收起弹注
        if (fnPanel != null) closeFnPanel()
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        fun label(res: Int): TextView = TextView(this).apply {
            text = getString(res)
            textSize = 13f
            setTextColor(themeText())
            setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
        }

        fun valueLabel(): TextView = TextView(this).apply {
            textSize = 13f
            setTextColor(themeText())
            val lp = LinearLayout.LayoutParams((64 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
            gravity = android.view.Gravity.CENTER
        }

        /** 0.4.23：滑块颜色跟随主题（系统 Light 主题的蓝色在纸色/夜间面板里很突兀）。 */
        fun tintSeeks(v: View) {
            if (v is SeekBar) {
                val c = android.content.res.ColorStateList.valueOf(themeLink())
                v.thumbTintList = c
                v.progressTintList = c
                v.progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(themeFaded(0x33))
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) tintSeeks(v.getChildAt(i))
        }

        fun rowButton(textRes: Int, selected: Boolean): Button =
            Button(this).apply {
                text = getString(textRes)
                textSize = 13f
                isAllCaps = false
                setSingleLine()
                minLines = 1
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                alpha = if (selected) 1f else 0.55f
                setTextColor(if (selected) themeLink() else themeFaded(0xCC))
                background = null // 去掉系统 Button 的浅色实心底，跟随面板主题
                stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

        fun restyle(b: Button, selected: Boolean) {
            b.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            b.alpha = if (selected) 1f else 0.55f
            b.setTextColor(if (selected) themeLink() else themeFaded(0xCC))
        }

        // M3：全部设置先存草稿，点「完成」才统一提交生效（主题/排版/边距/注释字号）
        var dTheme = themeId
        var dFs = fontPx
        var dLh = lineH
        var dFam = fontFam
        val dFn0 = ReaderPrefs.fnFont(this)

        // 主题
        box.addView(label(R.string.set_theme))
        val themeIds = intArrayOf(ReaderPrefs.THEME_WHITE, ReaderPrefs.THEME_PAPER, ReaderPrefs.THEME_NIGHT)
        val themeNames = intArrayOf(R.string.theme_white, R.string.theme_paper, R.string.theme_night)
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val themeBtns = themeNames.indices.map { i -> rowButton(themeNames[i], themeId == themeIds[i]) }
        themeBtns.forEachIndexed { i, b ->
            b.setOnClickListener {
                dTheme = themeIds[i] // 仅草稿，完成时应用
                themeBtns.forEachIndexed { j, bb -> restyle(bb, dTheme == themeIds[j]) }
            }
        }
        themeBtns.forEach { themeRow.addView(it) }
        box.addView(themeRow)

        // 字号
        box.addView(label(R.string.set_font))
        val fontVal = valueLabel()
        val fontSeek = SeekBar(this).apply {
            min = 14; max = 30
            progress = if (fontPx in 14..30) fontPx else 22
            fontVal.text = "${progress}px"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    fontVal.text = "${p}px"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    dFs = s.progress
                }
            })
        }
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fontRow.addView(fontSeek); fontRow.addView(fontVal)
        box.addView(fontRow)

        // 行距
        box.addView(label(R.string.set_line))
        val lineVal = valueLabel()
        val lineSeek = SeekBar(this).apply {
            min = 0; max = 20   // 1.20 ~ 2.20，步进 0.05
            progress = (if (lineH in 1.15..2.25) ((lineH - 1.2) / 0.05).toInt() else 6)
                .coerceIn(0, 20)
            lineVal.text = String.format(Locale.US, "%.2f", 1.2 + progress * 0.05)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    lineVal.text = String.format(Locale.US, "%.2f", 1.2 + p * 0.05)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    dLh = 1.2 + s.progress * 0.05
                }
            })
        }
        val lineRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lineRow.addView(lineSeek); lineRow.addView(lineVal)
        box.addView(lineRow)

        // 字族
        box.addView(label(R.string.set_family))
        val famValues = arrayOf("", "serif", "sans")
        val famNames = intArrayOf(R.string.fam_book, R.string.fam_serif, R.string.fam_sans)
        val famRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val famBtns = famNames.indices.map { i -> rowButton(famNames[i], fontFam == famValues[i]) }
        famBtns.forEachIndexed { i, b ->
            b.setOnClickListener {
                dFam = famValues[i]
                famBtns.forEachIndexed { j, bb -> restyle(bb, dFam == famValues[j]) }
            }
        }
        famBtns.forEach { famRow.addView(it) }
        box.addView(famRow)

        // 页边距（M2.2：上/下/左/右独立；M3 改为「完成」后统一应用）
        var mSeekL: SeekBar? = null
        var mSeekR: SeekBar? = null
        var mSeekT: SeekBar? = null
        var mSeekB: SeekBar? = null
        fun marginSlider(labelRes: Int, init: Int, max: Int): SeekBar {
            box.addView(label(labelRes))
            val mVal = valueLabel()
            val mSeek = SeekBar(this).apply {
                min = 0
                this.max = max
                progress = init
                mVal.text = "${progress}px"
            }
            mSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    mVal.text = "${v}px"
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            mSeek.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(mSeek)
            row.addView(mVal)
            box.addView(row)
            return mSeek
        }
        mSeekL = marginSlider(R.string.set_margin_left, mL.coerceIn(0, 48), 48)
        mSeekR = marginSlider(R.string.set_margin_right, mR.coerceIn(0, 48), 48)
        mSeekT = marginSlider(R.string.set_margin_top, mT.coerceIn(0, 96), 96)
        mSeekB = marginSlider(R.string.set_margin_bottom, mB.coerceIn(0, 96), 96)

        // 注释（弹注）字号（M2.2：10–24px，全局）
        box.addView(label(R.string.set_fn_font))
        val fVal = valueLabel()
        val fSeek = SeekBar(this).apply {
            min = 10
            max = 24
            progress = dFn0.coerceIn(10, 24)
            fVal.text = "${progress}px"
        }
        var dFn = dFn0
        fSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                fVal.text = "${v}px"
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                dFn = s.progress
            }
        })
        val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fSeek.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        fRow.addView(fSeek)
        fRow.addView(fVal)
        box.addView(fRow)

        // 自定义字体（M2.2：选择 TTF/OTF，导入后立即应用——独立流程不经「完成」）
        box.addView(label(R.string.set_custom_font))
        val fontBtn = themeFlatButton(
            if (fontFam == "custom") getString(R.string.font_in_use) else getString(R.string.pick_font_btn)
        ).apply { textSize = 14f }
        fontBtn.setOnClickListener {
            closeMenuOverlay()
            pickFont()
        }
        box.addView(fontBtn)

        fun commit() {
            // M3：统一提交
            val oldFs = fontPx; val oldLh = lineH; val oldFam = fontFam
            val oldML = mL; val oldMR = mR; val oldMT = mT; val oldMB = mB
            val themeChanged = dTheme != themeId
            themeId = dTheme
            ReaderPrefs.setTheme(this, themeId)
            fontPx = dFs
            lineH = dLh
            fontFam = dFam
            persistTypography()
            val marginChanged = mSeekL?.progress != oldML || mSeekR?.progress != oldMR ||
                mSeekT?.progress != oldMT || mSeekB?.progress != oldMB
            val typoChanged = dFs != oldFs || dLh != oldLh || dFam != oldFam
            if (marginChanged) {
                applyMargins(
                    mSeekL?.progress ?: oldML, mSeekR?.progress ?: oldMR,
                    mSeekT?.progress ?: oldMT, mSeekB?.progress ?: oldMB
                ) // 内部已按锚点重排
            } else if (dFam != oldFam) {
                // M3.12：字族变化必须重载章节——书内 CSS 的字体链前置发生在章节加载与
                // 资源拦截阶段，热更新（__setFont）改不到已加载的 CSS，会残留旧字体
                val a = when {
                    curAnchor >= 0 -> Anchor.Elem(curAnchor, curPage)
                    curTotal > 1 -> Anchor.Frac(curPage.toDouble() / (curTotal - 1))
                    else -> Anchor.First
                }
                loadSpine(spineIndex, a)
            } else if (typoChanged) {
                relayoutPreserve()
            }
            ReaderPrefs.setFnFont(this, dFn) // 弹注下次打开生效
            if (themeChanged) {
                applyThemeChrome()
                val p = pal()
                runJs(
                    "try{window.__setTheme('${p.bg}','${p.text}','${p.link}'," +
                        "${themeId == ReaderPrefs.THEME_NIGHT});}catch(e){}"
                )
            }
        }
        tintSeeks(box)
        // 0.4.23：统一浮层 80vw×80vh 居中；设置项较多，用 ScrollView 保证小屏可完整操作
        val sc = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        showMenuOverlay(
            getString(R.string.settings_title), sc,
            listOf(getString(R.string.action_done) to { commit(); closeMenuOverlay() })
        )
    }


    // ————— 信息条与进度（FR-04/FR-11） —————

    private fun updateInfo() {
        val book = epub ?: return
        val total = book.spine.size.coerceAtLeast(1)
        val pct = ((spineIndex + (curPage + 1.0) / curTotal) / total * 100).toInt().coerceIn(0, 100)
        infoBar.text = curTitle // M2.2：顶部=当前标题
        bottomBar.text = getString(R.string.info_fmt, pct, curPage + 1, curTotal, spineIndex + 1, total) // 底部=进度
        infoBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
    }

    private fun saveProgress() {
        if (bookId <= 0 || epub == null) return
        if (spineIndex == lastSavedSpine && curPage == lastSavedPage && curTotal == lastSavedTotal) return
        lastSavedSpine = spineIndex
        lastSavedPage = curPage
        lastSavedTotal = curTotal
        val book = epub ?: return
        val total = book.spine.size.coerceAtLeast(1)
        val pct = ((spineIndex + (curPage + 1.0) / curTotal) / total * 100).coerceIn(0.0, 100.0)
        val row = ProgressRow(spineIndex, curPage, curTotal, curAnchor, pct)
        onIo {
            runCatching { (application as App).db.saveProgress(bookId, row) }
                .onFailure { Logx.w(TAG, "进度保存失败", it) }
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    // ————— 旋转：保持位置重新分页 —————

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val frac = if (curTotal > 1) curPage.toDouble() / (curTotal - 1) else 0.0
        val a = if (curAnchor >= 0) Anchor.Elem(curAnchor, curPage) else Anchor.Frac(frac)
        loadSpine(spineIndex, a)
    }

    // ————— 资源拦截（虚拟资源通道，M0 同构） —————

    private fun intercept(url: Uri): WebResourceResponse? {
        if (destroyed) return null
        // M2.2：自定义字体经拦截通道提供（@font-face src 指向 reader.local，同源可加载）
        if (url.path == "/font/custom.ttf") {
            val f = File(ReaderPrefs.fontPath(this))
            if (f.exists()) return WebResourceResponse("font/ttf", null, f.inputStream())
            return null
        }
        if (url.host != HOST) return null
        val path = url.path ?: return null
        if (!path.startsWith("/book/$bookId/")) return null
        val rel = ZipPaths.normalize(ZipPaths.decode(path.removePrefix("/book/$bookId/")))
        val arch = archive ?: return null // 已关闭（onDestroy）→ 不再接触 ZipFile
        val mime = ZipPaths.mimeTypeFor(rel)
        // M3.12：外链 CSS 前置当前字体栈（缺字按书内 CSS 逐字符回退）。
        // 0.4.22：改写结果按「路径 + 前置栈」缓存，同一 CSS 被反复请求时不再重复解压与改写。
        if (mime == "text/css") {
            val stack = fontPrependStack()
            if (stack.isNotEmpty()) {
                val key = "$rel|$stack"
                val out = cssCache[key] ?: run {
                    val bytes = arch.entryBytes(rel) ?: return null
                    val patched = ReaderHtml.prependFontFamily(
                        String(bytes, ZipPaths.detectCharset(bytes)), stack
                    ).toByteArray(Charsets.UTF_8)
                    cssCache[key] = patched
                    patched
                }
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(out))
            }
        }
        val encoding =
            if (mime.startsWith("text/") || mime.contains("css") || mime.contains("xhtml") || mime.contains("xml")) "utf-8"
            else null
        // 0.4.22：图片 / 字体等二进制资源走 InputStream 流式交给 WebView，
        // 不再 readBytes 整块进内存；小资源由 EpubArchive 的 LRU 直接供流。
        val stream = arch.entryStream(rel) ?: return null
        return WebResourceResponse(mime, encoding, stream)
    }

    override fun onDestroy() {
        destroyed = true
        // ① 掐断在途扫描与所有 UI 回调，避免回调打到已销毁对象
        searchCancelled = true
        searchSession++ // 使所有在途回报过期
        searchPool?.shutdownNow()
        searchPool = null
        ui.removeCallbacksAndMessages(null)
        // ② 移除全部浮层（搜索浮层顺带收起输入法并打断扫描）
        closeMenuOverlay()
        closeFnPanel()
        closeImgOverlay()
        selPopup?.dismiss()
        selPopup = null
        // ③ 主 WebView：停加载 → 摘 JS 桥 → 清空页面 → 脱离视图树 → destroy
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface("ReaderBridge")
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        fnWv?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            runCatching { wv.destroy() }.onFailure { Logx.w(TAG, "销毁弹注 WebView 失败", it) }
        }
        fnWv = null
        // ④ 线程池与 ZipFile：任务开头检查 destroyed 后自行退出；
        //    EpubArchive 内部有锁与 closed 标志，杜绝并发关闭异常。
        io.shutdownNow()
        runCatching { archive?.close() }.onFailure { Logx.w(TAG, "关闭 EPUB 归档失败", it) }
        archive = null
        epub = null
        cssCache.clear()
        pendingSrchHtml.clear()
        super.onDestroy()
    }

    /** M2.2：字体选择结果——Reasily 式直接引用原文件（不复制），并整章重载使 @font-face 立即生效 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FONT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = UriPath.resolve(contentResolver, uri)
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, R.string.pick_font_fail, Toast.LENGTH_SHORT).show()
            return
        }
        ReaderPrefs.setFontPath(this, path) // 仅登记原路径，拦截通道按需读取
        fontFam = "custom"
        persistTypography()
        val a = when {
            curAnchor >= 0 -> Anchor.Elem(curAnchor, curPage)
            curTotal > 1 -> Anchor.Frac(curPage.toDouble() / (curTotal - 1))
            else -> Anchor.First
        }
        loadSpine(spineIndex, a) // 整章重载：@font-face 随页面注入，避免延迟到下次翻章才生效
        Toast.makeText(this, R.string.font_ok, Toast.LENGTH_SHORT).show()
    }
}
