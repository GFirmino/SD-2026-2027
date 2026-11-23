import argparse, json, os, random, re, shutil, subprocess, threading, time, urllib.request
from pathlib import Path
from processos_windows import child_job

ROOT=Path(__file__).parent
SOURCE=Path(os.environ.get('COMINDIR05_REPO',r'C:\Projects\SDT\SD-2026-2027'))/'Sprints'/'E1-COMINDIR05'
JAVA=r'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
JAVAC=r'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
IPFS=str(ROOT/'runtime'/'ipfs.exe')
API='http://127.0.0.1:16001/api/v0'
JOB=child_job()

def now(): return int(time.time()*1000)
def read(path):
    try:return Path(path).read_text(encoding='utf-8',errors='replace')
    except FileNotFoundError:return ''
def wait(test,timeout=35,label='condicao'):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        r=test()
        if r:return r
        time.sleep(.08)
    raise AssertionError('Timeout: '+label)
def compile_all():
    for project in ('no','produtor','processador'):
        dest=ROOT/'build'/project;dest.mkdir(parents=True,exist_ok=True)
        subprocess.run([JAVAC,'--release','11','-encoding','UTF-8','-Xlint:all','-d',str(dest),
                        *map(str,(SOURCE/('raft04-'+project)/'src/raft04').glob('*.java'))],check=True)

def core_tests():
    build=ROOT/'build'/'core'
    sources=list((SOURCE/'raft04-no/src/raft04').glob('*.java'))
    sources+=[SOURCE/'raft04-produtor/src/raft04/HeartbeatMonitor.java',ROOT/'CoreTest.java',ROOT/'RecoveryTest.java']
    subprocess.run([JAVAC,'--release','11','-encoding','UTF-8','-Xlint:all','-d',str(build),*map(str,sources)],check=True)
    for main in ('CoreTest','RecoveryTest'):
        folder=ROOT/'execucoes'/('unidade-'+main+'-'+str(now()));folder.mkdir(parents=True)
        p=subprocess.run([JAVA,'-cp',str(build),'raft04.'+main],cwd=folder,capture_output=True,text=True,encoding='utf-8')
        (folder/'resultado.log').write_text(p.stdout+p.stderr,encoding='utf-8')
        print(p.stdout,flush=True);assert p.returncode==0,p.stderr

class Process:
    def __init__(self,cmd,cwd,name,env=None):
        self.output=Path(cwd)/(name+'.log');self.stream=self.output.open('wb',buffering=0)
        self.p=subprocess.Popen(cmd,cwd=cwd,stdin=subprocess.PIPE,stdout=self.stream,stderr=subprocess.STDOUT,env=env)
        if JOB:JOB.add(self.p)
    def send(self,s):self.p.stdin.write((s+'\n').encode());self.p.stdin.flush()
    def stop(self):
        if self.p.poll() is None:self.p.kill();self.p.wait(timeout=12)
        self.stream.close()

class System:
    def __init__(self,folder,fixed=False,partitions=range(3),build=None,barrier=None):
        self.folder=Path(folder);self.folder.mkdir(parents=True,exist_ok=True)
        self.nodes={};self.clients=[];self.fixed=fixed;self.build=Path(build) if build else ROOT/'build'
        self.parts=list(partitions); self.barrier=barrier
        order=[(k,i)for k in self.parts for i in range(1,4)];random.shuffle(order)
        for k,i in order:self.start(k,i)
        def ready():
            for p in self.nodes.values():
                if p.p.poll() is not None:raise RuntimeError('No terminou no arranque. Consulte '+str(p.output))
            return all(read(p.output).count('SUBSCRITO:')>=3 for p in self.nodes.values())
        wait(ready,label='subscricoes nos')
    def command(self,project,main,*args):
        cmd=[JAVA,'-Xms16m','-Xmx96m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Draft04.api='+API]
        if self.fixed:cmd+=['-Draft04.eleicaoMin=6000','-Draft04.eleicaoMax=6000']
        if self.barrier:cmd+=['-Draft04.arranque='+str(self.barrier)]
        return cmd+['-cp',str(self.build/project),'raft04.'+main,*map(str,args)]
    def start(self,k,i):
        p=Process(self.command('no','PartitionNode',k,i,3),self.folder,f'p{k}-n{i}-{now()}')
        self.nodes[k,i]=p;return p
    def entries(self,k,event):
        out=[]
        for i in range(1,4):
            for line in read(self.folder/f'eventos-p{k}-n{i}.txt').splitlines():
                f=line.split(';')
                if len(f)>2 and f[1]==event:
                    values=dict(x.split('=',1) for x in f[3:] if '='in x)
                    out.append((int(values.get('termo',0)),int(f[0]),i,line))
        return sorted(out)
    def leader(self,k,after=0):
        es=[e for e in self.entries(k,'LIDER') if e[0]>after and self.nodes[k,e[2]].p.poll() is None]
        return es[-1] if es else None
    def leaders(self):return wait(lambda:all(self.leader(k) for k in self.parts),45,'lideres')
    def client(self,project,main):
        p=Process(self.command(project,main,3),self.folder,project+'-'+str(now()));self.clients.append(p)
        wait(lambda:read(p.output).count('SUBSCRITO:')>=3,label='subscricoes '+project);return p
    def producer(self):return self.client('produtor','Producer')
    def processor(self):return self.client('processador','AlarmProcessor')
    def kill(self,k,i):
        stamp=now()
        with (self.folder/'falhas.txt').open('a',encoding='utf-8')as f:f.write(f'p{k};n{i};{stamp}\n')
        self.nodes[k,i].stop();return stamp
    def isolate(self,k,i,value=True):
        path=self.folder/f'isolar-p{k}-n{i}'
        if value:path.touch()
        else:path.unlink(missing_ok=True)
        with (self.folder/'rede.txt').open('a',encoding='utf-8')as f:f.write(f'{now()};p{k};n{i};'+('isolar' if value else 'reparar')+'\n')
    def same(self,k):
        rows=[read(self.folder/f'no-p{k}-n{i}.txt') for i in range(1,4)]
        return rows[0]==rows[1]==rows[2]
    def settle(self):
        wait(lambda:all(self.same(k)for k in self.parts),45,'convergencia');time.sleep(2.5)
        wait(lambda:all(self.same(k)for k in self.parts),45,'convergencia final')
    def check(self,causal=True):
        self.settle()
        # Os escritores sao parados antes de analisar para nao obter snapshots de instantes diferentes.
        self.stop()
        checks=['RaftCheck']+(['CausalCheck']if causal else [])
        for name in checks:
            cmd=self.command('no',name,3,self.folder)
            if name=='RaftCheck'and (self.folder/'falhas.txt').exists():cmd.append(str(self.folder/'falhas.txt'))
            r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8')
            (self.folder/(name+'.log')).write_text(r.stdout,encoding='utf-8')
            assert r.returncode==0,(name,self.folder,r.stdout[-3000:])
    def stop(self):
        for p in self.clients+list(self.nodes.values()):p.stop()

def trials(folder,quick=False):
    results={}
    def run(name,fn,**kw):
        print('ENSAIO '+name,flush=True);s=System(folder/name,**kw)
        try:
            fn(s);s.check();results[name]=read(s.folder/'raft-check.txt')
            print('OK '+name,flush=True)
        finally:s.stop()
    def figure(s):
        s.leaders();p=s.producer()
        for command in ('S02 20.1','S05 23.7'):p.send(command);time.sleep(.35)
        wait(lambda:all(len(read(s.folder/f'no-p1-n{i}.txt').splitlines())==2 for i in range(1,4)),label='duas leituras replicadas')
        lead=s.leader(1);i=lead[2]
        # Esperar um lote vazio para deixar uma janela quase completa antes do envio seguinte.
        old=len(read(s.folder/f'hb-p1-n{i}.txt').splitlines())
        wait(lambda:len(read(s.folder/f'hb-p1-n{i}.txt').splitlines())>old,5,'heartbeat')
        p.send('S02 20.4');wait(lambda:len(read(s.folder/f'no-p1-n{i}.txt').splitlines())==3,2,'registo pendente')
        s.kill(1,i);p.send('S05 23.9')
        wait(lambda:s.leader(1,lead[0]),30,'substituto');p.send('S02 20.6');time.sleep(3)
        s.start(1,i);wait(lambda:s.entries(1,'TRUNCADO'),25,'truncagem');s.settle()
        assert '20.4' not in read(s.folder/f'no-p1-n{i}.txt')
    run('a-figura',figure)
    if quick:return results
    for fixed,count in ((False,10),(True,5)):
        for index in range(count):run(('b-fixo-'if fixed else 'b-aleatorio-')+str(index+1),lambda s:s.leaders(),fixed=fixed)
    for index in range(3):
        def fail(s):
            s.leaders();s.processor();p=s.producer();lead=s.leader(1)
            p.send('rajada 300 10');time.sleep(.8);s.kill(1,lead[2]);time.sleep(30);s.start(1,lead[2])
            wait(lambda:'RAJADA conclu' in read(p.output),60,'fim da rajada');time.sleep(5)
        run('c-falha-'+str(index+1),fail)
    def isolation(s):
        s.leaders();s.processor();p=s.producer();lead=s.leader(1)
        p.send('rajada 300 10');time.sleep(.8);s.isolate(1,lead[2]);time.sleep(30)
        assert s.leader(1,lead[0]),'Maioria nao elegeu substituto'
        s.isolate(1,lead[2],False);wait(lambda:s.entries(1,'TRUNCADO'),25,'truncagem isolamento')
        s.settle()
        follower=next(i for i in range(1,4)if i!=s.leader(1)[2]);term=s.leader(1)[0]
        s.isolate(1,follower);time.sleep(60);s.isolate(1,follower,False)
        wait(lambda:s.leader(1,term),35,'nova eleicao apos regresso');time.sleep(4)
    run('d-isolamentos',isolation)
    def quorum(s):
        s.leaders();p=s.producer();lead=s.leader(1);survivor=next(i for i in range(1,4)if i!=lead[2])
        killed=[i for i in range(1,4)if i!=survivor]
        for i in killed:s.kill(1,i)
        for j in range(30):p.send('S02 '+str(20+j/100));time.sleep(1)
        assert not s.leader(1,lead[0]),'Eleicao sem maioria'
        s.start(1,killed[0]);wait(lambda:s.leader(1,lead[0]),35,'maioria reposta')
        s.start(1,killed[1]);time.sleep(4)
    run('e-sem-maioria',quorum)
    return results

def daemon():
    import socket
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',16001))==0:
            raise RuntimeError('Porto 16001 ocupado. Termine a outra sessao COMINDIR05 antes de iniciar esta.')
    repo=ROOT/'runtime'/'ipfs';repo.mkdir(parents=True,exist_ok=True)
    env=os.environ.copy();env['IPFS_PATH']=str(repo)
    if not (repo/'config').exists():subprocess.run([IPFS,'init','--profile=test'],env=env,stdout=subprocess.DEVNULL,check=True)
    cfg=json.loads((repo/'config').read_text())
    cfg['Addresses']['API']='/ip4/127.0.0.1/tcp/16001';cfg['Addresses']['Gateway']='/ip4/127.0.0.1/tcp/16003'
    cfg['Addresses']['Swarm']=['/ip4/127.0.0.1/tcp/16002'];cfg['Bootstrap']=[]
    (repo/'config').write_text(json.dumps(cfg))
    p=Process([IPFS,'daemon','--enable-pubsub-experiment'],ROOT/'runtime','kubo-'+str(now()),env)
    wait(lambda:'Daemon is ready'in read(p.output),25,'Kubo');return p

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--quick',action='store_true');ap.add_argument('--demo',action='store_true');ap.add_argument('--unit',action='store_true');args=ap.parse_args()
    compile_all()
    if not args.demo:core_tests()
    if args.unit:raise SystemExit(0)
    d=daemon();folder=ROOT/'execucoes'/time.strftime('%Y%m%d-%H%M%S')
    try:
        if args.demo:
            s=System(folder)
            try:
                s.leaders();s.processor();p=s.producer();print('Sistema pronto. Ficheiros: '+str(folder),flush=True)
                print('Comandos: S02 29.3 | rajada 300 10 | estado | falhar 1 | reiniciar 1 2 | isolar 1 2 | reparar 1 2 | sair',flush=True)
                while True:
                    cmd=input('> ').strip();a=cmd.split()
                    if not a:continue
                    if a[0]=='sair':break
                    if a[0]=='estado':print({k:s.leader(k) for k in range(3)},flush=True)
                    elif a[0]=='falhar':k=int(a[1]);s.kill(k,s.leader(k)[2])
                    elif a[0]=='reiniciar':s.start(int(a[1]),int(a[2]))
                    elif a[0]in('isolar','reparar'):s.isolate(int(a[1]),int(a[2]),a[0]=='isolar')
                    else:
                        before=read(p.output).count('RAJADA conclu')
                        p.send(cmd)
                        if a[0].startswith('rajada'):wait(lambda:read(p.output).count('RAJADA conclu')>before,180,'fim da rajada')
                        time.sleep(5)
                        for k in range(3):
                            lead=s.leader(k)
                            rows=read(s.folder/f'no-p{k}-n{lead[2]}.txt').splitlines() if lead else []
                            print(f'p{k}: {len(rows)} registos; lider '+str(lead[:3] if lead else None),flush=True)
                            for row in rows[-2:]:print('  '+row,flush=True)
                # Restaurar os membros para analisar a coincidencia final.
                for k in range(3):
                    for i in range(1,4):
                        s.isolate(k,i,False)
                        if s.nodes[k,i].p.poll() is not None:s.start(k,i)
                time.sleep(5);s.check();print(read(folder/'raft-check.txt'))
            finally:s.stop()
        else:
            result=trials(folder,args.quick)
            (folder/'resultados.json').write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
            print('ENSAIOS: OK '+str(folder),flush=True)
    finally:d.stop()
