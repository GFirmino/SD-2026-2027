
'use strict';
const model=JSON.parse(document.querySelector('#model').textContent), $=s=>document.querySelector(s), NS='http://www.w3.org/2000/svg';
let current=0, expanded=false, selected=null, scale=.78, W=0,H=0;
const svg=$('#canvas');
function elem(tag,attrs={},parent=svg,text=null){const n=document.createElementNS(NS,tag);for(const[k,v]of Object.entries(attrs))n.setAttribute(k,v);if(text!==null)n.textContent=text;parent.appendChild(n);return n;}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function size(){svg.setAttribute('width',W*scale);svg.setAttribute('height',H*scale);$('#zoom').textContent=Math.round(scale*100)+'%';}
function fit(){scale=Math.min(1,Math.max(.25,($('#viewport').clientWidth-16)/W));size();}
function detail(name){
 selected=name;
 document.querySelectorAll('.node').forEach(n=>{n.classList.toggle('selected',n.dataset.id===name);n.classList.toggle('dim',name!==null&&n.dataset.id!==name&&!model.edges.some(e=>(e.a===name&&e.b===n.dataset.id)||(e.b===name&&e.a===n.dataset.id)));});
 document.querySelectorAll('[data-edge]').forEach(n=>{const e=model.edges[+n.dataset.edge],on=name===null||e.a===name||e.b===name;n.classList.toggle('dim',!on);n.classList.toggle('hot',name!==null&&on);});
 if(!name){$('#badge').textContent='LEITURA DO DIAGRAMA';$('#classtitle').textContent='Seleciona uma classe';$('#description').textContent='Clica numa classe para ver a sua responsabilidade, os seus membros e as relações no código.';$('#details').innerHTML='';return;}
 const c=model.classes[name];$('#badge').textContent=c.kind+' · '+c.project;$('#classtitle').textContent=name;$('#description').textContent=c.description;
 let h='<h3>Atributos selecionados</h3><ul>'+c.attrs.map(s=>'<li><code>'+esc(s)+'</code></li>').join('')+'</ul><h3>Operações selecionadas</h3><ul>'+c.methods.map(s=>'<li><code>'+esc(s)+'</code></li>').join('')+'</ul>';
 if(!c.attrs.length&&!c.methods.length)h='';
 h+='<h3>Relações no código</h3><ul>'+model.edges.filter(e=>e.a===name||e.b===name).map(e=>'<li><b>'+esc(e.a)+'</b> → <b>'+esc(e.b)+'</b><br>'+esc(e.label)+(e.m?' · destino '+esc(e.m):'')+'</li>').join('')+'</ul>';
 h+='<h3>Fontes Java</h3><ul>'+c.sources.map(s=>'<li><a href="'+encodeURI(s.path)+'">'+esc(s.path)+'</a></li>').join('')+'</ul>';$('#details').innerHTML=h;
}
function render(){
 const view=model.views[current];$('#viewtitle').textContent=view.title;$('#viewdesc').textContent=view.desc;
 $('#tabs').replaceChildren();model.views.forEach((v,i)=>{const b=document.createElement('button');b.textContent=v.title;b.className=i===current?'active':'';b.setAttribute('aria-pressed',i===current);b.onclick=()=>{current=i;selected=null;render();fit();$('#viewport').scrollTo(0,0);};$('#tabs').appendChild(b);});
 svg.replaceChildren();const defs=elem('defs');
 let mark=elem('marker',{id:'arrow',viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:8,markerHeight:8,orient:'auto'},defs);elem('path',{d:'M1 1L9 5L1 9',fill:'none',stroke:'#607c90','stroke-width':1.3},mark);
 mark=elem('marker',{id:'diamond',viewBox:'0 0 16 12',refX:0,refY:6,markerWidth:13,markerHeight:10,orient:'auto'},defs);elem('path',{d:'M0 6L7 1L14 6L7 11Z',fill:'#607c90'},mark);
 const nodes={},cardW=364,gapX=85,gapY=91,pad=42;let y=pad;
 for(let start=0;start<view.names.length;start+=view.cols){let rowH=0;view.names.slice(start,start+view.cols).forEach((name,col)=>{const c=model.classes[name],members=expanded?c.attrs.length+c.methods.length:0,height=expanded?91+members*19:105;nodes[name]={x:pad+col*(cardW+gapX),y,w:cardW,h:height};rowH=Math.max(rowH,height);});for(const name of view.names.slice(start,start+view.cols))nodes[name].rowBottom=y+rowH;y+=rowH+gapY;}
 W=pad*2+view.cols*cardW+(view.cols-1)*gapX;H=y-gapY+pad;svg.setAttribute('viewBox',`0 0 ${W} ${H}`);
 const layer=elem('g');
 model.edges.forEach((e,index)=>{const a=nodes[e.a],b=nodes[e.b];if(!a||!b)return;let x1,y1,x2,y2,path,labelX,labelY;
  if(a.y===b.y){x1=a.x+a.w*.5;y1=a.y;x2=b.x+b.w*.5;y2=b.y;const mid=a.y-19-(index%3)*7;path=`M${x1} ${y1}V${mid}H${x2}V${y2}`;labelX=(x1+x2)/2;labelY=mid-4;}
  else {const down=b.y>a.y;x1=a.x+a.w*(.32+(index%3)*.17);y1=down?a.y+a.h:a.y;x2=b.x+b.w*(.32+(index%3)*.17);y2=down?b.y:b.y+b.h;const sourceLane=down?a.rowBottom+19+(index%4)*12:a.y-19-(index%3)*10;const targetLane=down?b.y-19-(index%3)*10:b.rowBottom+19+(index%4)*12;const trunk=a.x+a.w+22+(index%3)*6;path=`M${x1} ${y1}V${sourceLane}H${trunk}V${targetLane}H${x2}V${y2}`;labelX=(trunk+x2)/2;labelY=targetLane-4;}
  elem('path',{d:path,class:'edge '+e.type,'data-edge':index,'marker-end':'url(#arrow)',...(e.type==='composition'?{'marker-start':'url(#diamond)'}:{})},layer);
  const label=e.label+(e.m?' ['+e.m+']':'');elem('text',{x:labelX,y:labelY,'text-anchor':'middle',class:'elabel','data-edge':index},layer,label);
 });
 for(const[name,p]of Object.entries(nodes)){const c=model.classes[name],g=elem('g',{class:'node','data-id':name,tabindex:0,role:'button','aria-label':name,transform:`translate(${p.x} ${p.y})`});
  elem('rect',{width:p.w,height:p.h,rx:8,class:'body'},g);elem('path',{d:`M8 0H${p.w-8}Q${p.w} 0 ${p.w} 8V59H0V8Q0 0 8 0`,class:'head'},g);
  elem('text',{x:14,y:20,class:'stereotype'},g,`${c.kind} · ${c.project}`);elem('text',{x:14,y:43,class:'name'},g,name);
  if(expanded){let yy=79;c.attrs.forEach(s=>{elem('text',{x:14,y:yy,class:'member'},g,s);yy+=19;});elem('line',{x1:0,x2:p.w,y1:yy-5,y2:yy-5,class:'divider'},g);yy+=14;c.methods.forEach(s=>{elem('text',{x:14,y:yy,class:'member'},g,s);yy+=19;});}
  else {elem('text',{x:14,y:83,class:'stereotype'},g,`${c.attrs.length} atributos / grupos · ${c.methods.length} operações`);}
  g.addEventListener('click',()=>detail(selected===name?null:name));g.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();detail(name);}});
 }
 size();detail(selected);
}
$('#more').onclick=()=>{scale=Math.min(1.8,scale+.1);size();};$('#less').onclick=()=>{scale=Math.max(.25,scale-.1);size();};$('#fit').onclick=fit;$('#reset').onclick=()=>{$('#search').value='';detail(null);};$('#members').onclick=()=>{expanded=!expanded;$('#members').textContent=expanded?'Ocultar membros':'Mostrar membros';render();fit();};$('#print').onclick=()=>window.print();
$('#search').addEventListener('input',e=>{const q=e.target.value.toLowerCase().trim();if(!q){detail(null);return;}const name=Object.keys(model.classes).find(n=>n.toLowerCase()===q)||Object.keys(model.classes).find(n=>n.toLowerCase().includes(q));if(!name)return;if(!model.views[current].names.includes(name)){current=model.views.findIndex(v=>v.names.includes(name));render();fit();}detail(name);});
render();fit();
