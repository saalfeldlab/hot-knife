# PaintHeightField Keyboard Shortcuts

## File Operations
- **Ctrl+S** - Save height field to N5
- **Ctrl+U** - Undo (reload height field from disk)

## Navigation
- **Ctrl+C** - Go to z=0 surface
- **Ctrl+0** - Toggle z=0 line overlay
- **Ctrl+2** - Toggle scale display overlay (shows current zoom level)
- **Ctrl+F** - Move horizontal right (2000 pixels at current scale)
- **Ctrl+D** - Move horizontal left (2000 pixels at current scale)
- **Ctrl+R** - Move vertical up (1500 pixels at current scale)
- **Ctrl+V** - Move vertical down (1500 pixels at current scale)

## Height Field Editing Tools

### Push/Pull Brush (Adjust height values)
- **SPACE + Left Mouse** - Push height field up (+0.1 × magnitude per click)
- **SPACE + Right Mouse** - Pull height field down (-0.1 × magnitude per click)
- **SPACE + Middle Mouse** - Pull height field down (alternative)
- **SPACE + Scroll** - Change brush radius
- **SPACE** - Show brush cursor and activate tool

### Smooth Brush (Gaussian smoothing)
- **Q + Left Mouse** - Apply Gaussian smoothing to height field
- **Q + Scroll** - Change smooth brush radius
- **Shift+Q + Scroll** - Change smoothing sigma (strength)
- **Q** - Show brush cursor and activate tool

### Weighted Smooth Brush (Gradient-weighted smoothing)
- **W + Left Mouse** - Apply gradient-weighted smoothing (preserves edges)
- **W + Scroll** - Change weighted smooth brush radius
- **Shift+W + Scroll** - Change weighted smoothing sigma (strength)
- **W** - Show brush cursor and activate tool

## Notes
- All brush tools use Gaussian brush masks for smooth modifications
- Brush radius and sigma can be adjusted continuously with scroll wheel
- Height field magnitude can be adjusted in the "Height Field Magnitude" panel
- The z=0 line shows where the target surface is positioned after offset
- Current gradient (green) updates with edits, input gradient (red) shows original
