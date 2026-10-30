# A harmless GUI child; never loads client code or account data.
Add-Type -AssemblyName System.Windows.Forms
$form = New-Object Windows.Forms.Form
$form.Text = 'TON-1111 descendant capture self-test'
$form.Width = 640; $form.Height = 320
$label = New-Object Windows.Forms.Label
$label.Dock = 'Fill'; $label.TextAlign = 'MiddleCenter'
$label.Text = "Measured GUI child PID $PID`r`nParent PID must come from Win32_Process`r`nNo BiliPai client launched"
$form.Controls.Add($label)
$timer = New-Object Windows.Forms.Timer
$timer.Interval = 30000
$timer.Add_Tick({ $form.Close() }); $timer.Start()
[Windows.Forms.Application]::Run($form)
