import sys

filepath = '/Users/leleli/AndroidStudioProjects/Oscope/app/src/main/java/com/example/oscope/AudioEngineViewModel.kt'

with open(filepath, 'r') as f:
    lines = f.readlines()

# Lines are 0-indexed; line 1320 is index 1319, line 1321 is index 1320
for i in [1319, 1320]:
    line = lines[i]
    indent = len(line) - len(line.lstrip(' '))
    if indent == 40:
        lines[i] = '                                    ' + line.lstrip(' ')
        print(f'Fixed line {i+1}: indent {indent} -> 36')
    else:
        # Different indent than expected - show what's there
        print(f'Line {i+1}: indent={indent} content={repr(line[:60])}')

with open(filepath, 'w') as f:
    f.writelines(lines)

print('Done')
