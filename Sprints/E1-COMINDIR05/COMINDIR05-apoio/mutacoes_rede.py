import subprocess,shutil,json,time,threading
from pathlib import Path
from integracao import *

dest=ROOT/'mutacoes-rede'/time.strftime('%Y%m%d-%H%M%S');dest.mkdir(parents=True)
def variant(name):
    source=dest/name/'src';source.mkdir(parents=True);build=dest/name/'build'
    for project in ('produtor','processador'):shutil.copytree(ROOT/'build'/project,build/project)
    for p in (SOURCE/'raft04-no/src/raft04').glob('*.java'):
        text=p.read_text(encoding='utf-8')
        if name=='atualizacao' and p.name=='RaftState.java':text=text.replace('&& upToDate;','&& true;')
        if name=='obsoleto':
            if p.name=='RaftState.java':text=text.replace('if(t<term) return false;','if(t<term) { if(role==Role.SEGUIDOR){leader=from;resetTimer();return true;} return false; }')
            if p.name=='PartitionNode.java':text=text.replace('raft.term()!=b.term || ','')
        (source/p.name).write_text(text,encoding='utf-8')
    subprocess.run([JAVAC,'--release','11','-encoding','UTF-8','-d',str(build/'no'),*map(str,source.glob('*.java'))],check=True)
    return build

def check(s,name):
    s.stop();out={}
    for cls in ('RaftCheck','CausalCheck'):
        r=subprocess.run(s.command('no',cls,3,s.folder),capture_output=True,text=True,encoding='utf-8')
        out[cls]={'exit':r.returncode,'saida':r.stdout+r.stderr}
    (dest/name/'resultado.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(name,[(k,v['exit'])for k,v in out.items()],flush=True)

compile_all();d=daemon()
try:
    name='atualizacao';build=variant(name);folder=dest/name/'dados';folder.mkdir()
    # Preparar um prefixo do termo 1 nos dois nos atualizados e um no atrasado vazio.
    # O termo atual 1 e conservado por todos; o no atrasado tem prazo de 6s e os outros 12s.
    for i in range(1,4):
        (folder/f'estado-p1-n{i}.txt').write_text('1;0\n')
        if i>1:(folder/f'no-p1-n{i}.txt').write_text('1;1;S02;20.1;2026-11-07T11:00:00;1;0,1,0\n2;1;S05;23.7;2026-11-07T11:00:01;2;0,2,0\n')
    class Staggered(System):
        def command(self,project,main,*args):
            cmd=super().command(project,main,*args)
            if main=='PartitionNode'and int(args[0])==1:
                t=6000 if int(args[1])==1 else 12000
                cmd[1:1]=[f'-Draft04.eleicaoMin={t}',f'-Draft04.eleicaoMax={t}']
            return cmd
    s=Staggered(folder,build=build)
    try:
        s.leaders();assert s.leader(1)[2]==1,'O candidato atrasado nao foi eleito'
        s.settle();assert s.entries(1,'TRUNCADO'),'Historicos atualizados nao foram removidos'
        check(s,name)
    finally:s.stop()
    name='obsoleto';build=variant(name);s=System(dest/name/'dados',build=build)
    try:
        s.leaders();s.processor();p=s.producer();old=s.leader(1)
        p.send('rajada 300 10');time.sleep(.8);s.isolate(1,old[2]);time.sleep(30)
        assert s.leader(1,old[0]),'Maioria nao elegeu lider'
        s.isolate(1,old[2],False);time.sleep(12);s.settle();check(s,name)
    finally:s.stop()
    print('MUTACOES DE REDE: OK '+str(dest),flush=True)
finally:d.stop()
