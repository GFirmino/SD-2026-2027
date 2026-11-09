from integracao import read


def rows(system, k, i):
    return [line.split(';') for line in read(system.folder / f'no-p{k}-n{i}.txt').splitlines()
            if len(line.split(';')) == 7]


def fields(line):
    return dict(part.split('=', 1) for part in line.split(';')[3:] if '=' in part)


def node_state(system, k, i):
    if system.nodes[k, i].p.poll() is not None:
        persisted = read(system.folder / f'estado-p{k}-n{i}.txt').strip().split(';')[0]
        return 'PARADO', int(persisted or '0')
    role, term = 'SEGUIDOR', 0
    for line in read(system.folder / f'eventos-p{k}-n{i}.txt').splitlines():
        parts = line.split(';')
        if len(parts) < 3:
            continue
        data = fields(line)
        event = parts[1]
        if event == 'TERMO':
            term = int(data.get('termo', term))
            role = 'SEGUIDOR'
        elif event == 'CANDIDATO':
            role, term = 'CANDIDATO', int(data['termo'])
        elif event == 'LIDER':
            role, term = 'LÍDER', int(data['termo'])
        elif event in ('DEMISSAO', 'NOVO_LIDER'):
            role = 'SEGUIDOR'
    return role, term


def active_leader(system, k, after=0):
    candidates = []
    for i in range(1, 4):
        role, term = node_state(system, k, i)
        if role == 'LÍDER' and term > after:
            candidates.append((term, i))
    return max(candidates) if candidates else None


def first_batch(system, k, leader):
    term, i = leader
    for line in read(system.folder / f'hb-p{k}-n{i}.txt').splitlines():
        parts = line.split(';')
        if len(parts) == 7 and int(parts[0]) == term:
            return int(parts[2])
    return None


if __name__ == '__main__':
    from painel import main
    raise SystemExit(main())
