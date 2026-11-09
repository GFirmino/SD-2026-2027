import subprocess, shutil, json, time
from pathlib import Path
from integracao import ROOT,SOURCE,JAVA,JAVAC

variants={
 'maioria':('RaftState.java','if(votes.size()<=groupSize/2)','if(votes.size()<groupSize/2)'),
 'persistencia':('RaftState.java','private void persist() {','private void persist() { if (true) return;'),
 'atualizacao':('RaftState.java','&& upToDate;','&& true;'),
 'obsoleto':('RaftState.java','if(t<term) return false;','if(t<term) { /* falha: aceitar termo antigo */ }'),
 'relogios':('PartitionNode.java','restore(); hb=0;pending.clear();','restore(); lamport=0; vector=new VectorClock(n); hb=0;pending.clear();'),
}
dest=ROOT/'mutacoes'/time.strftime('%Y%m%d-%H%M%S');dest.mkdir(parents=True)
results={}
for name,(filename,before,after)in variants.items():
    folder=dest/name;src=folder/'src';src.mkdir(parents=True);build=folder/'build';run=folder/'dados';run.mkdir()
    for file in (SOURCE/'raft04-no/src/raft04').glob('*.java'):
        content=file.read_text(encoding='utf-8')
        if file.name==filename:
            assert before in content,(name,before)
            content=content.replace(before,after,1)
        if name=='obsoleto' and file.name=='PartitionNode.java':
            content=content.replace('raft.term()!=b.term || ','')
        (src/file.name).write_text(content,encoding='utf-8')
    subprocess.run([JAVAC,'--release','11','-encoding','UTF-8','-d',str(build),*map(str,src.glob('*.java')),str(ROOT/'MutationTest.java')],check=True)
    cmd=[JAVA,'-Xmx96m','-XX:ActiveProcessorCount=2','-Draft04.api=http://127.0.0.1:1/api/v0','-cp',str(build)]
    executed=subprocess.run(cmd+['raft04.MutationTest',name],cwd=run,capture_output=True,text=True,encoding='utf-8',timeout=25)
    (folder/'execucao.log').write_text(executed.stdout+executed.stderr,encoding='utf-8');assert executed.returncode==0,(name,executed.stderr)
    checks={}
    for check in ('RaftCheck','CausalCheck'):
        r=subprocess.run(cmd+['raft04.'+check,'3',str(run)],cwd=run,capture_output=True,text=True,encoding='utf-8')
        checks[check]={'exit':r.returncode,'saida':r.stdout+r.stderr}
    results[name]={'execucao':executed.stdout,'verificadores':checks}
    print(name,[(k,v['exit'])for k,v in checks.items()],flush=True)
(dest/'resultados.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print('MUTACOES: OK '+str(dest))
