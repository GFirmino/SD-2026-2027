import subprocess,json,urllib.request,urllib.error,sys

credential=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',text=True,capture_output=True,check=True)
fields=dict(line.split('=',1)for line in credential.stdout.splitlines()if '='in line)
token=fields['password']
def call(path,data=None):
    req=urllib.request.Request('https://api.github.com'+path,data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'SD-COMINDIR05'})
    with urllib.request.urlopen(req,timeout=25)as response:return json.load(response)
login=call('/user')['login'];print('Conta Git:',login)
if '--create'in sys.argv:
    if login!='TiagoLopes13':raise SystemExit('Conta diferente da indicada para o fork pessoal; PR nao criado.')
    branch='tarefa-8-COMINDIR5'
    try:
        existing=call('/repos/cunhaestgv/SD-2026-2027/pulls?state=open&head='+login+':'+branch)
        if existing:print('PR:',existing[0]['html_url']);raise SystemExit(0)
        body='Implementação da eleição de líder por maioria em três nós por partição, com termos e votos persistentes, recuperação TCP e truncagem de históricos divergentes.\n\nInclui RaftCheck, adaptação dos relógios e monitores, instruções de execução e relatório dos ensaios de arranque, falha, isolamento e perda de quórum. Compilação para Java 11, testes de estado/protocolo/recuperação e ensaios com Kubo 0.39.0.\n\nO algoritmo mantém os limites da ficha: sem commit por maioria, Pre-Vote ou reenvio de leituras.'
        pr=call('/repos/cunhaestgv/SD-2026-2027/pulls',{'title':'Implementação COMINDIR05','base':'master','head':login+':'+branch,'body':body,'maintainer_can_modify':True})
        print('PR:',pr['html_url'])
    except urllib.error.HTTPError as e:
        print('REST: HTTP',e.code,e.read().decode())
        if e.code!=404:raise SystemExit(1)
        base=call('/repos/cunhaestgv/SD-2026-2027')['node_id']
        head=call('/repos/'+login+'/SD-2026-2027')['node_id']
        query='mutation($input:CreatePullRequestInput!){createPullRequest(input:$input){pullRequest{url}}}'
        result=call('/graphql',{'query':query,'variables':{'input':{'repositoryId':base,'headRepositoryId':head,'baseRefName':'master','headRefName':login+':'+branch,'title':'Implementação COMINDIR05','body':body,'maintainerCanModify':True}}})
        if result.get('errors'):print('PR nao criado:',json.dumps(result['errors'],ensure_ascii=False));raise SystemExit(1)
        print('PR:',result['data']['createPullRequest']['pullRequest']['url'])
