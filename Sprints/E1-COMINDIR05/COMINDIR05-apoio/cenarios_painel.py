"""Os cenarios originais do painel, com particao selecionavel na falha/isolamento."""
import time
from demonstracao import rows, fields, node_state, active_leader, first_batch


class Cancelled(Exception):
    pass


def choose_partition():
    while True:
        value = input('Partição p0/p1/p2 [p1] > ').strip().lower() or 'p1'
        if value in ('q', 'cancelar'):
            raise Cancelled()
        token = value[1:] if value.startswith('p') else value
        if token in ('0', '1', '2'):
            return int(token)
        print('Disponíveis: p0, p1, p2. q = cancelar.', flush=True)


def record_after(system, k, before, sensor, temperature):
    lead = active_leader(system, k)
    if lead:
        return next((r for r in rows(system, k, lead[1])[before[lead[1]]:]
                     if r[2] == sensor and float(r[3]) == temperature), None)
    return None


def reading(panel, producer, restore):
    system = panel.system
    before = {k: {i: len(rows(system, k, i)) for i in range(1, 4)} for k in (1, 2)}
    panel.results = []
    panel.note('IN         p1/S02 temp=29.3')
    producer.send('S02 29.3')
    original = panel.wait(lambda: record_after(system, 1, before[1], 'S02', 29.3), 'GUARDAR LEITURA')
    alarm = panel.wait(lambda: record_after(system, 2, before[2], 'ALARM-S02', 29.3), 'AGUARDAR ALARME')
    restore(panel)
    panel.results = ['GRUPO  SENSOR         TEMP    SEQ  TERMO   L  VETOR']
    for k, r in ((1, original), (2, alarm)):
        panel.results.append(f'p{k}     {r[2]:<14} {r[3]:<7} {r[0]:>3} {r[1]:>6} {r[5]:>3}  {r[6]}')
    panel.results.append('p1/leitura -> p2/alarme | cópias iguais: 3/3 em cada grupo')


def failure(panel, producer, k, restore, leader_ready):
    system = panel.system
    restore(panel)
    old_term, old = active_leader(system, k)
    panel.results = []
    failed_at = system.kill(k, old)
    panel.note(f'PARAR      p{k}/n{old} termo={old_term}')
    sensor = f'S0{k+1}'
    producer.send(f'{sensor} 20.123456')
    panel.note(f'IN         p{k}/{sensor} temp=20.123456 [sem líder]')
    new_term, new = panel.wait(lambda: leader_ready(system, k, old_term), f'ELEIÇÃO p{k} | 6-12 s típicos')
    delay = (first_batch(system, k, (new_term, new))-failed_at)/1000
    system.start(k, old)
    panel.note(f'REINICIAR  p{k}/n{old}')
    restore(panel)
    panel.results = [f'PARTIÇÃO     p{k}',
                     f'LÍDER        n{old} (termo {old_term}) -> n{new} (termo {new_term})',
                     f'SUBSTITUIÇÃO {delay:.3f} s até ao primeiro lote',
                     f'REGRESSO     n{old}: {node_state(system, k, old)[0]}',
                     'RÉPLICAS     3/3 iguais']


def isolation(panel, producer, k, restore, leader_ready):
    system = panel.system
    restore(panel)
    old_term, old = active_leader(system, k)
    panel.results = []
    before = len(rows(system, k, old))
    sensor = f'S0{k+1}'
    temperature = round(22 + (time.time_ns() % 1000000)/1000000, 6)
    existing = {float(r[3]) for r in rows(system, k, old)}
    while temperature in existing:
        temperature = round(temperature + .000001, 6)
    system.isolate(k, old)
    panel.note(f'ISOLAR     p{k}/n{old} termo={old_term}')
    producer.send(f'{sensor} {temperature}')
    panel.wait(lambda: len(rows(system, k, old)) > before, 'GUARDAR NO ISOLADO')
    new_term, new = panel.wait(lambda: leader_ready(system, k, old_term), f'ELEIÇÃO NA MAIORIA p{k}')
    panel.results = [f'PARTIÇÃO       p{k}',
                     f'LÍDER ISOLADO  n{old} / termo {old_term}',
                     f'LÍDER MAIORIA  n{new} / termo {new_term}',
                     f'ESCRITA LOCAL  {sensor} {temperature} °C [apenas n{old}]']
    panel.state('REDE PARTICIONADA | dois líderes em termos diferentes')
    panel.screen(force=True, menu=False)
    input('[Enter] Reparar rede > ')
    count = len(system.entries(k, 'TRUNCADO'))
    system.isolate(k, old, False)
    panel.note(f'REPARAR    p{k}/n{old}')
    panel.wait(lambda: len(system.entries(k, 'TRUNCADO')) > count, 'RECONCILIAR')
    restore(panel)
    if any(r[2] == sensor and float(r[3]) == temperature for r in rows(system, k, old)):
        raise RuntimeError('A escrita isolada ainda consta do histórico.')
    detail = fields(system.entries(k, 'TRUNCADO')[-1][3])
    panel.results = [f'PARTIÇÃO     p{k} | TERMOS isolado={old_term}, maioria={new_term}',
                     f'TRUNCAGEM    seq inicial={detail.get("de")} | removidos={detail.get("descartados")}',
                     f'ESCRITA      {sensor} {temperature} °C -> descartada',
                     'RÉPLICAS     3/3 iguais | commit por maioria: não implementado']
