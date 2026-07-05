import sys, os

files = [
    'condense/install.sh',
    'condense/src/test/java/com/condense/analytics/AsciiGraphRendererTest.java',
    'condense/src/test/java/com/condense/filter/git/GitStatusFilterTest.java'
]

for fpath in files:
    if not os.path.exists(fpath):
        continue
    with open(fpath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    for line in lines:
        if '──' in line and (line.lstrip().startswith('#') or line.lstrip().startswith('//')):
            continue
        new_lines.append(line)
        
    with open(fpath, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
print("Removed dividers.")
