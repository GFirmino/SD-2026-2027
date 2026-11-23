"""Operacoes manuais; os alvos sao sempre escolhidos no painel."""
import math
import time
from demonstracao import rows, node_state, active_leader


class Cancelled(Exception):
    pass


def answer(prompt, default=None):
    suffix = f' [{default}]' if default is not None else ''
    value = input(prompt + suffix + ' > ').strip()
    if value.lower() in ('q', 'cancelar'):
        raise Cancelled()
    return value if value else str(default) if default is not None else ''


def partition(sensor):
    # String.hashCode de Java opera sobre unidades UTF-16, incluindo pares substitutos.
    encoded = sensor.encode('utf-16-be')
    value = 0
    for j in range(0, len(encoded), 2):
        value = (31 * value + int.from_bytes(encoded[j:j+2], 'big')) & 0xffffffff
    if value >= 0x80000000:
        value -= 0x100000000
    return value % 3


def choose_partition():
    while True:
        value = answer('Partição p0/p1/p2').lower()
        token = value[1:] if value.startswith('p') else value
        if token in ('0', '1', '2'):
            return int(token)
        print('Inválida. Disponíveis: p0, p1, p2 (N=3). q = cancelar.', flush=True)


def choose_node(system, k):
    lead = active_leader(system, k)
    default = 'L' if lead else 'n1'
    while True:
        value = answer(f'Nó n1/n2/n3 ou L=líder de p{k}', default).lower()
        if value == 'l':
            leaders = [i for i in range(1, 4) if node_state(system, k, i)[0] == 'LÍDER']
            if len(leaders) == 1:
                return leaders[0]
            print('Indica n1, n2 ou n3: há zero ou vários nós a assumir liderança.', flush=True)
            continue
        token = value[1:] if value.startswith('n') else value
        if token in ('1', '2', '3'):
            return int(token)
        print('Nó inválido. Usa n1, n2, n3 ou L; q = cancelar.', flush=True)


def number(prompt, default, minimum=0, maximum=None, integer=False):
    while True:
        value = answer(prompt, default).replace(',', '.')
        try:
            parsed = int(value) if integer else float(value)
            if not math.isfinite(parsed) or parsed < minimum or (maximum is not None and parsed > maximum):
                raise ValueError()
            return parsed
        except (ValueError, OverflowError):
            print('Valor inválido. q = cancelar.', flush=True)


def watch(panel, seconds, label='ACOMPANHAR'):
    end = time.monotonic() + seconds
    panel.state(label)
    while time.monotonic() < end:
        panel.status = f'{label} | {max(0, end-time.monotonic()):.1f} s'
        panel.screen()
        time.sleep(.15)


def send_reading(panel, producer):
    k = choose_partition()
    while True:
        sensor = answer('Sensor', f'S0{k+1}')
        invalid = (not sensor or ';' in sensor or any(c.isspace() or ord(c) < 32 or 127 <= ord(c) <= 159 for c in sensor))
        if invalid:
            print('Sensor inválido: usar um identificador sem espaços nem ponto e vírgula.', flush=True)
            continue
        actual = partition(sensor)
        if actual != k:
            print(f'{sensor} pertence a p{actual}, não a p{k}. Escolhe outro sensor ou q para cancelar.', flush=True)
            continue
        break
    temp = number('Temperatura °C', 29.3, minimum=-1e308)
    system = panel.system
    before = {(p, i): len(rows(system, p, i)) for p in range(3) for i in range(1, 4)}
    sent_count = len(read_sent(system))
    panel.results = []
    busy = getattr(panel, 'burst_mark', 0) > completed_bursts(producer)
    producer.send(f'{sensor} {temp}')
    panel.note(f'IN         p{k}/{sensor} temp={temp}')
    if busy:
        panel.results = [f'COMANDO p{k}/{sensor} temp={temp}',
                         'EM FILA no produtor, após as rajadas pendentes',
                         'Acompanhar: opção 6']
        return
    def published():
        if producer.p.poll() is not None:
            return True
        for line in read_sent(system)[sent_count:]:
            f = line.split(';')
            if len(f) == 4 and f[1] == f'p{k}' and f[2] == sensor and float(f[3]) == temp:
                return True
        return False
    panel.wait(published, 'PUBLICAR IN', 15)
    if producer.p.poll() is not None:
        raise RuntimeError('O produtor terminou. Consulte ' + str(producer.output))
    watch(panel, 5, 'OBSERVAR LEITURA/ALARME')
    targets = {sensor}
    if temp >= 28 and not sensor.startswith('ALARM-'):
        targets.add('ALARM-' + sensor)
    found = {}
    for p in range(3):
        for i in range(1, 4):
            for row in rows(system, p, i)[before[p, i]:]:
                if row[2] in targets and float(row[3]) == temp:
                    key = (p, tuple(row))
                    found.setdefault(key, []).append(f'n{i}')
    panel.results = [f'IN p{k}/{sensor} temp={temp} | enviado', 'GRUPO SENSOR        NÓS       SEQ TERMO   L VETOR']
    for (p, row), nodes in list(found.items())[:3]:
        panel.results.append(f'p{p}    {row[2]:<13.13} {",".join(nodes):<9} {row[0]:>3} {row[1]:>5} {row[5]:>3} {row[6]}')
    if not found:
        panel.results.append('REGISTO: não observado na janela de 5 s')
    elif len(found) > 3:
        panel.results.append('Outras ocorrências: consultar ficheiros da sessão')


def read_sent(system):
    from integracao import read
    return read(system.folder / 'produtor.txt').splitlines()


def completed_bursts(producer):
    from integracao import read
    return read(producer.output).count('RAJADA conclu')


def node_action(panel, action):
    system = panel.system
    k = choose_partition()
    i = choose_node(system, k)
    before_role, before_term = node_state(system, k, i)
    isolated = (system.folder / f'isolar-p{k}-n{i}').exists()
    unchanged = False
    if action == 'PARAR':
        if system.nodes[k, i].p.poll() is None:
            system.kill(k, i)
        else:
            unchanged = True
    elif action == 'REINICIAR':
        if system.nodes[k, i].p.poll() is not None:
            system.nodes[k, i].stop()
            process = system.start(k, i)
            def ready():
                if process.p.poll() is not None:
                    raise RuntimeError('Nó terminou no arranque: ' + str(process.output))
                from integracao import read
                return read(process.output).count('SUBSCRITO:') >= 3
            panel.wait(ready, f'ARRANCAR p{k}/n{i}')
        else:
            unchanged = True
    elif action == 'ISOLAR':
        if not isolated:
            system.isolate(k, i)
        else:
            unchanged = True
    elif action == 'REPARAR':
        if isolated:
            system.isolate(k, i, False)
        else:
            unchanged = True
    panel.note(f'{action:<10} p{k}/n{i}' + (' [sem alteração]' if unchanged else ''))
    watch(panel, 1, f'{action} p{k}/n{i}')
    after_role, after_term = node_state(system, k, i)
    network = 'ISOLADA' if (system.folder / f'isolar-p{k}-n{i}').exists() else 'OK'
    panel.results = [f'ALVO    p{k}/n{i} | {action}' + (' | sem alteração' if unchanged else ''),
                     f'PAPEL   {before_role} -> {after_role}',
                     f'TERMO   {before_term} -> {after_term} | REDE {network}',
                     'Acompanhar eleição/eventos: opção 6']


def follow(panel):
    seconds = number('Acompanhar durante quantos segundos', 15, maximum=300)
    watch(panel, seconds)


def burst(panel, producer):
    count = number('Quantidade de leituras', 300, minimum=1, maximum=100000, integer=True)
    interval = number('Intervalo entre envios (ms)', 10, maximum=60000, integer=True)
    panel.burst_mark = max(getattr(panel, 'burst_mark', 0), completed_bursts(producer)) + 1
    producer.send(f'rajada {count} {interval}')
    panel.note(f'RAJADA     n={count} intervalo={interval}ms')
    panel.results = [f'RAJADA    {count} leituras | intervalo {interval} ms',
                     'SENSORES  S01-S10; partição calculada pelo produtor',
                     'ESTADO    comando enviado; execução em segundo plano']
