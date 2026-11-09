"""Painel de terminal: estado real dos processos, eventos e resultados."""
import os
import sys
import time
import traceback
from collections import deque
from datetime import datetime
from integracao import ROOT, System, compile_all, daemon, read
from demonstracao import rows, fields, node_state, active_leader, first_batch
from cenarios_painel import Cancelled, choose_partition, reading, failure, isolation


def terminal_control():
    if not sys.stdout.isatty():
        return False
    if os.name != 'nt':
        return True
    import ctypes
    from ctypes import wintypes
    api = ctypes.windll.kernel32
    api.GetStdHandle.restype = wintypes.HANDLE
    api.GetConsoleMode.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    api.SetConsoleMode.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    handle = api.GetStdHandle(-11)
    mode = wintypes.DWORD()
    return bool(api.GetConsoleMode(handle, ctypes.byref(mode))
                and api.SetConsoleMode(handle, mode.value | 0x0004))


class Panel:
    def __init__(self):
        self.system = None
        self.producer = None
        self.status = 'COMPILAR'
        self.events = deque(maxlen=5)
        self.offsets = {}
        self.results = []
        self.dynamic = terminal_control()
        self.last_render = 0

    def note(self, text):
        self.events.append(text)

    def collect(self):
        if self.system is None:
            return
        new = []
        for path in self.system.folder.glob('eventos-p*-n*.txt'):
            lines = read(path).splitlines()
            old = self.offsets.get(path, 0)
            for line in lines[old:]:
                parts = line.split(';')
                if len(parts) < 3 or parts[1] not in ('CANDIDATO', 'LIDER', 'DEMISSAO', 'TRUNCADO'):
                    continue
                data = fields(line)
                node = path.stem.rsplit('-', 1)[1]
                detail = ('de=' + data.get('de', '?') + ' n=' + data.get('descartados', '?')
                          if parts[1] == 'TRUNCADO' else 'termo=' + data.get('termo', '?'))
                new.append((int(parts[0]), f'{parts[1]:10} {parts[2]}/{node} {detail}'))
            self.offsets[path] = len(lines)
        for _, text in sorted(new):
            self.note(text)

    def screen(self, force=False, menu=True):
        self.collect()
        if not force and (not self.dynamic or time.monotonic()-self.last_render < .5):
            return
        self.last_render = time.monotonic()
        lines = ['COMINDIR05 | ' + self.status, '-' * 77,
                 'GRUPO  NÓ   PAPEL       TERMO  SEQ    L    VETOR         REDE']
        if self.system is None:
            lines.append('  --    --   --           --   --   --    --            --')
        else:
            for k in range(3):
                for i in range(1, 4):
                    role, term = node_state(self.system, k, i)
                    records = rows(self.system, k, i)
                    last = records[-1] if records else None
                    network = 'ISOLADA' if (self.system.folder / f'isolar-p{k}-n{i}').exists() else 'OK'
                    role = 'LIDER' if role == 'LÍDER' else role
                    lines.append(f'p{k:<5} n{i}   {role:<10} {term:>5} {last[0] if last else "0":>4}'
                                 f' {last[5] if last else "0":>4}    {last[6] if last else "0,0,0":<13} {network}')
        lines += ['-' * 77, 'EVENTOS / AÇÕES']
        lines += list(self.events) or ['  --']
        lines += ['-' * 77, 'RESULTADO']
        lines += self.results or ['  --']
        if menu:
            lines += ['-' * 77,
                      '[1] Leitura/alarme  [2] Falhar líder  [3] Isolar líder',
                      '[4] Atualizar      [5] Ficheiros     [0] Verificar/sair']
        text = '\n'.join(lines) + '\n'
        if self.dynamic:
            # O mesmo painel é redesenhado; não se acumula uma transcrição no ecrã.
            text = '\x1b[H\x1b[2J' + text
        print(text, end='', flush=True)

    def state(self, status):
        self.status = status
        if not self.dynamic:
            print('[' + status + ']', flush=True)
        self.screen()

    def wait(self, test, status, timeout=45):
        self.state(status)
        start = time.monotonic()
        while time.monotonic()-start < timeout:
            result = test()
            if result:
                self.screen()
                return result
            self.screen()
            time.sleep(.15)
        raise RuntimeError('Timeout: ' + status)


def restore(panel):
    system = panel.system
    for k in range(3):
        for i in range(1, 4):
            if (system.folder / f'isolar-p{k}-n{i}').exists():
                system.isolate(k, i, False)
            if system.nodes[k, i].p.poll() is not None:
                system.start(k, i)
    panel.wait(lambda: all(active_leader(system, k) for k in range(3)), 'CONFIRMAR LÍDERES')
    panel.wait(lambda: all(system.same(k) for k in range(3)), 'REPLICAR')
    # Drenar as publicações/observações em trânsito e confirmar de novo.
    end = time.monotonic() + 2.5
    panel.wait(lambda: time.monotonic() >= end, 'ESTABILIZAR')
    panel.wait(lambda: all(system.same(k) for k in range(3)), 'REPLICAR')


def leader_ready(system, k, after=0):
    lead = active_leader(system, k, after)
    return lead if lead and first_batch(system, k, lead) else None


def finish(panel):
    if panel.producer is not None:
        panel.producer.stop()  # Parar novas entradas antes de drenar e verificar.
    restore(panel)
    panel.state('VERIFICAR E TERMINAR')
    panel.system.check()
    report = read(panel.system.folder/'raft-check.txt')
    summary = next(s for s in report.splitlines() if s.startswith('RESUMO;'))
    data = dict(part.split('=', 1) for part in summary.split(';')[1:])
    panel.results = ['SEGURANÇA    OK | líderes por termo <= 1',
                     'CÓPIAS       OK | 3/3 iguais por grupo',
                     'RELÓGIOS     OK | CausalCheck',
                     f'PERDAS       {data["perdidas"]} | TRUNCADOS {data["truncados"]}',
                     'Perdas/truncagens podem resultar das falhas e isolamentos.']
    panel.status = 'TERMINADO'
    panel.screen(force=True, menu=False)
    print('Resultados: ' + str(panel.system.folder), flush=True)


def main():
    panel = Panel()
    transport = None
    try:
        panel.screen(force=True, menu=False)
        compile_all()
        panel.state('INICIAR KUBO')
        transport = daemon()
        panel.state('ARRANCAR 9 NÓS')
        panel.system = System(ROOT/'execucoes'/('painel-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f')))
        panel.wait(lambda: all(leader_ready(panel.system, k) for k in range(3)), 'ELEIÇÃO INICIAL | 6-12 s típicos')
        panel.state('LIGAR PRODUTOR/PROCESSADOR')
        panel.system.processor()
        producer = panel.system.producer()
        panel.producer = producer
        while True:
            panel.status = 'PRONTO'
            panel.screen(force=True)
            choice = input('Opção > ').strip()
            if choice == '0':
                finish(panel)
                return 0
            try:
                if choice == '1':
                    reading(panel, producer, restore)
                elif choice == '2':
                    k = choose_partition()
                    failure(panel, producer, k, restore, leader_ready)
                elif choice == '3':
                    k = choose_partition()
                    isolation(panel, producer, k, restore, leader_ready)
                elif choice == '4':
                    pass
                elif choice == '5':
                    os.startfile(panel.system.folder)
                    panel.note('FICHEIROS  pasta da sessão aberta')
                else:
                    panel.note('OPÇÃO      inválida; usar 0..5')
            except Cancelled:
                panel.note('CANCELADO   sem operação')
    except (EOFError, KeyboardInterrupt):
        print('\nINTERROMPIDO | processos da sessão a terminar', flush=True)
        return 0
    except Exception as error:
        folder = panel.system.folder if panel.system else ROOT
        path = folder/'erro-painel.txt'
        path.write_text(traceback.format_exc(), encoding='utf-8')
        print(f'\nERRO: {error}\nLog: {path}', flush=True)
        return 1
    finally:
        if panel.system:
            panel.system.stop()
        if transport:
            transport.stop()


if __name__ == '__main__':
    raise SystemExit(main())
