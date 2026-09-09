package org.researchzosho.librarian;

/**
 * The map, inside the site frame at {@code /map?focus=…}: no external scripts, no frameworks — a canvas, a force
 * layout in a hundred lines, and the JSON from {@code /v1/map}. Names are dots, subjects are squares joined to every
 * name filed under them, a dashed line is a disputed claim. Click a dot to move to it; the legend is the kinds.
 */
final class MapPage {

    private MapPage() { }

    /** The map's markup and script, for the site frame (the nav on top, the viewer's theme): a search bar, the canvas, the side panel. */
    static String body(String focus) {
        String f = focus == null ? "" : focus.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        return "<style>.mapbar{display:flex;flex-wrap:wrap;gap:.5em;align-items:center;margin:.4em 0 .6em}.mapbar input{flex:1;min-width:14em;font:inherit;padding:.4em .6em;border:1px solid var(--line);background:var(--card);color:var(--ink)}"
                + ".mapwrap{display:flex;gap:0;align-items:stretch;border:1px solid var(--line);background:var(--card)}canvas#c{display:block;flex:1;min-width:0}"
                + "#side{width:22em;flex:none;max-height:calc(100vh - 12em);overflow:auto;padding:.6em .9em;border-left:1px solid var(--line);font-size:.9em}#side b{color:var(--accent)}"
                + "@media(max-width:800px){.mapwrap{flex-direction:column}#side{width:auto;border-left:0;border-top:1px solid var(--line)}}</style>"
                + "<div class=\"mapbar\"><input id=\"q\" value=\"" + f + "\" placeholder=\"a person, a place, a work, a subject…\"> "
                + "<button id=\"go\">Map</button> <span class=\"k\">depth <select id=\"d\"><option>1</option><option selected>2</option><option>3</option></select></span></div>"
                + "<div class=\"mapwrap\"><canvas id=\"c\"></canvas><div id=\"side\"></div></div>"
                + "<p class=\"k\">Every line is a claim; a dashed line is a disputed one. Subjects are the shelf labels: a name is joined to every subject its claims are filed under. Click a dot to move to it.</p>"
                + "<script>\n"
                + "window.onerror=(msg,src,line,col)=>{const s=document.getElementById('side');if(s)s.innerHTML='<b>The map hit an error:</b> '+String(msg).replace(/</g,'&lt;')+' (line '+line+')<br>'+s.innerHTML;};\n"
                + "const KIND={person:'#e8b04a',place:'#5aa7d6',event:'#9b6bd1',document:'#8fbf8f',organisation:'#d67c5a',work:'#c3402f',concept:'#9a948a',subject:'#e0654f'};\n"
                + "const css=n=>getComputedStyle(document.documentElement).getPropertyValue(n).trim();\n"
                + "const c=document.getElementById('c'),ctx=c.getContext('2d');let W,H;function size(){const wrap=c.parentElement;W=c.width=Math.max(300,wrap.clientWidth-(innerWidth>800?document.getElementById('side').offsetWidth:0));H=c.height=Math.max(360,Math.min(innerHeight-190,720))}size();addEventListener('resize',()=>{size();draw()});\n"
                + "let nodes=[],edges=[],focus=null;\n"
                + "async function load(q){const r=await fetch('/v1/map',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({focus:q,depth:+document.getElementById('d').value,k:60})});const j=await r.json();\n"
                + " const side=document.getElementById('side');const tries=(j.suggestions||[]).map(s=>'<a href=\"#\" data-n=\"'+esc(s.label)+'\">'+esc(s.label)+'</a> <span class=k>'+s.kind+' · '+s.degree+'</span>').join('<br>');\n"
                + " if(!j.node){nodes=[];edges=[];side.innerHTML=(q?'<b>No claim names \"'+esc(q)+'\"</b> yet. A name appears here once a claim about it is on the shelves, spelled as the claims spell it.':'<b>Type a name</b>: a person, a place, a work, a subject.')+(tries?'<br><br><span class=k>Names the map knows best:</span><br>'+tries:'<br><br><span class=k>The map is empty: no claim has the shape someone · did · something yet.</span>');bindTries();draw();return}\n"
                + " const byId={};nodes=j.nodes.map((n,i)=>{const a=i/j.nodes.length*6.28;const o={...n,x:W/2+Math.cos(a)*Math.min(W,H)/3,y:H/2+Math.sin(a)*Math.min(W,H)/3,vx:0,vy:0};byId[n.id]=o;return o});focus=byId[j.node.id];if(focus){focus.x=W/2;focus.y=H/2}\n"
                + " edges=j.edges.map(e=>({...e,a:byId[e.from],b:byId[e.to]})).filter(e=>e.a&&e.b);\n"
                + " const link=n=>n.kind==='subject'?'<a href=\"/search?subject='+encodeURIComponent(n.also&&n.also[0]||n.label)+'\">'+esc(n.label)+'</a>':esc(n.label);\n"
                + " side.innerHTML=(j.resolved_from?'<span class=k>no name is exactly \"'+esc(j.resolved_from)+'\"; showing the nearest:</span><br>':'')+'<b>'+link(j.node)+'</b> <span class=k>['+j.node.kind+']</span>'+(j.node.wikidata?' <span class=k>'+j.node.wikidata+'</span>':'')+'<br><br>'+edges.map(e=>esc(e.a.label)+' <span class=k>— '+esc(e.predicate)+' →</span> '+esc(e.b.label)+' <a class=k href=\"/entry/'+encodeURIComponent(e.finding)+'\">['+esc(e.finding.slice(0,6))+' '+e.state+']</a>').join('<br>')+(j.open&&j.open.length?'<br><br><b>open</b><br>'+j.open.map(esc).join('<br>'):'')+(tries?'<br><br><span class=k>Names the map knows best:</span><br>'+tries:'');bindTries();\n"
                + " for(let i=0;i<300;i++)step();draw()}\n"
                + "function bindTries(){document.querySelectorAll('#side a[data-n]').forEach(a=>a.onclick=ev=>{ev.preventDefault();document.getElementById('q').value=a.dataset.n;load(a.dataset.n)})}\n"
                + "function esc(s){return String(s).replace(/[&<>]/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[m]))}\n"
                + "function step(){for(const n of nodes){n.vx*=.85;n.vy*=.85}for(let i=0;i<nodes.length;i++)for(let j=i+1;j<nodes.length;j++){const a=nodes[i],b=nodes[j];let dx=b.x-a.x,dy=b.y-a.y,d=Math.hypot(dx,dy)||1,f=2000/(d*d);a.vx-=dx/d*f;a.vy-=dy/d*f;b.vx+=dx/d*f;b.vy+=dy/d*f}\n"
                + " for(const e of edges){let dx=e.b.x-e.a.x,dy=e.b.y-e.a.y,d=Math.hypot(dx,dy)||1,f=(d-140)*.02;e.a.vx+=dx/d*f;e.a.vy+=dy/d*f;e.b.vx-=dx/d*f;e.b.vy-=dy/d*f}\n"
                + " for(const n of nodes){if(n===focus){n.x=W/2;n.y=H/2;continue}n.vx+=(W/2-n.x)*.002;n.vy+=(H/2-n.y)*.002;n.x=Math.max(20,Math.min(W-20,n.x+n.vx));n.y=Math.max(20,Math.min(H-20,n.y+n.vy))}}\n"
                + "function draw(){const ink=css('--ink')||'#1e1b16',k=css('--k')||'#6b6459',line=css('--line')||'#e2dccd';ctx.clearRect(0,0,W,H);for(const e of edges){ctx.beginPath();ctx.setLineDash(e.disputed?[5,5]:e.predicate==='is filed under'?[2,4]:[]);ctx.strokeStyle=e.predicate==='is filed under'?'#e0654f':e.state==='accepted'?'#3f8a4f':k;ctx.lineWidth=e.predicate==='is filed under'?1:1.5;ctx.moveTo(e.a.x,e.a.y);ctx.lineTo(e.b.x,e.b.y);ctx.stroke();ctx.setLineDash([]);if(e.predicate!=='is filed under'){ctx.fillStyle=k;ctx.font='11px Georgia';ctx.fillText(e.predicate,(e.a.x+e.b.x)/2+4,(e.a.y+e.b.y)/2-4)}}\n"
                + " for(const n of nodes){const r=n===focus?11:n.kind==='subject'?9:6+Math.min(6,n.degree);ctx.beginPath();if(n.kind==='subject'){ctx.rect(n.x-r,n.y-r,2*r,2*r)}else{ctx.arc(n.x,n.y,r,0,6.28)}ctx.fillStyle=KIND[n.kind]||'#9a948a';ctx.fill();if(n.private){ctx.strokeStyle='#c3402f';ctx.stroke()}ctx.fillStyle=ink;ctx.font=(n===focus?'bold 14px':'13px')+' Georgia';ctx.fillText(n.label,n.x+r+4,n.y+4)}\n"
                + " let x=12;ctx.font='12px Georgia';for(const kk in KIND){ctx.fillStyle=KIND[kk];if(kk==='subject')ctx.fillRect(x,H-18,10,10);else{ctx.beginPath();ctx.arc(x+5,H-13,5,0,6.28);ctx.fill()}ctx.fillStyle=k;ctx.fillText(kk,x+14,H-9);x+=ctx.measureText(kk).width+30}}\n"
                + "c.addEventListener('click',ev=>{const x=ev.offsetX*(c.width/c.clientWidth),y=ev.offsetY*(c.height/c.clientHeight);for(const n of nodes)if(Math.hypot(n.x-x,n.y-y)<14){document.getElementById('q').value=n.label;load(n.label);return}});\n"
                + "document.getElementById('go').onclick=()=>load(document.getElementById('q').value);document.getElementById('q').addEventListener('keydown',e=>{if(e.key==='Enter')load(e.target.value)});\n"
                + "load(document.getElementById('q').value);\n"
                + "</script>";
    }
}
