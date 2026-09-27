================================================================================
  n1neslim "peppermint"  --  BEGINNER GUIDE  (the only file you need to read)
  For: X96 Mini TV box, Amlogic S905W chip, board name p281
================================================================================

WHAT THIS IS (plain English)
----------------------------
This folder is a complete little toolkit that BUILDS an installable zip file
(a "custom ROM package") for your TV box. It does NOT compile Android from
scratch (that takes 200+ GB and days). Instead it takes an ANDROID 9 system
image (a file called system.img), converts it into the format custom
recoveries understand, wraps it together with a boot image and an install
script, and signs it so a recovery like TWRP accepts it.

What you get at the end:  out/n1neslim_v1_ota.zip   <- this is your "OS" package

SAFETY - READ FIRST
-------------------
* Flashing wrong files can "brick" the box. It is almost always recoverable
  on Amlogic boxes with the USB Burning Tool (a tiny hole labeled Reset next
  to the power jack = Maskrom mode). Still: only flash when you're ok with
  re-doing the whole thing.
* The demo images in this kit are TINY FAKE images just so the build works
  end-to-end on your PC. They are NOT bootable Android. To make a REAL OS
  zip you must drop in real files (see STEP 4 below).
* Nothing here touches your PC's partitions. All output goes in the "out"
  folder. You cannot break Windows by running these tools.


================================================================================
STEP 1 - INSTALL WSL2 (your Linux mini-system inside Windows)
================================================================================
1. Press the Windows key, type: PowerShell        Right-click -> Run as administrator
2. Paste this and press Enter:
       wsl --install
3. RESTART the computer when it asks.
4. After restart, an "Ubuntu" window opens and installs itself.
   It asks you twice:
       Enter new UNIX username:   (type anything lowercase, e.g. you)
       Enter new UNIX password:   (type it - NOTHING shows while typing,
                                   that is normal. Press Enter again to confirm)
5. Test it: in the Ubuntu window type:
       uname -a
   If you see a line with "Linux" in it, you are done with Step 1.

TIP: In WSL, your Windows C: drive lives at /mnt/c . Files copy between
Windows and Linux through there.


================================================================================
STEP 2 - INSTALL THE TOOLS (one command)
================================================================================
In the Ubuntu window, paste this whole line and press Enter.
It will ask for your (invisible) password once, then run for a few minutes:

    sudo apt update && sudo apt install -y openjdk-17-jdk-headless brotli zip unzip openssl

Check it worked:
    java -version        <- should print something with "17" in it
    brotli --version     <- should print "brotli 1.x"


================================================================================
STEP 3 - COPY THIS FOLDER INTO LINUX AND BUILD THE DEMO
================================================================================
1. In Windows Explorer, find where you unzipped this folder. Note the path,
   e.g.  C:\Users\YourName\Downloads\n1neslim_peppermint_kit
2. In the Ubuntu window type (replace YourName with yours):

       cp -r /mnt/c/Users/YourName/Downloads/n1neslim_peppermint_kit ~/n1neslim
       cd ~/n1neslim
       chmod +x tools/n1neslim-peppermint/build_n1neslim.sh

3. Build the demo package with ONE command:

       ./tools/n1neslim-peppermint/build_n1neslim.sh

4. Watch it work. When you see:

       SUCCESS: out/n1neslim_v1_ota.zip

   ...you have just built a real, signed OTA-style zip. High five.
   (The pre-built copy in the "out" folder is identical - you can inspect it.)

5. Prove the zip is good:

       java -jar tools/n1neslim-peppermint/bin/n1neslim.jar verify --zip out/n1neslim_v1_ota.zip
       -> last line should say "OK"

       unzip -l out/n1neslim_v1_ota.zip
       -> you should see META-INF/com/google/android/update-binary,
          updater-script, system.new.dat.br, system.transfer.list, boot.img


================================================================================
STEP 4 - MAKE IT A REAL OS (not the demo)
================================================================================
The pipeline is: real Android 9 files -> system.img -> our tool -> signed zip.
You need TWO real files:

  * system.img  - an Android 9 (Pie) system image for p281/S905W
  * boot.img    - matching kernel+ramdisk boot image

Easiest legitimate source: download a LineageOS-16 (Android 9) build for
"p281" (X96 Mini). Two options:

  OPTION A (lazy, recommended): the LineageOS zip ALREADY contains
  system.new.dat.br + system.transfer.list. Extract those two files from it
  and skip straight to packing:

      unzip lineage-16.0-xxxx-p281.zip "system.new.dat.br" "system.transfer.list" -d mypayload/
      # also grab boot.img if present, otherwise use the one from the zip

  OPTION B (full): get/generate a raw system.img, then let the tool convert
  it (this is exactly what the demo run did, but with your real image):

      SYSTEM_IMG=./mypayload/system.img  BOOT_IMG=./mypayload/boot.img \
      OUT_DIR=./out  ./tools/n1neslim-peppermint/build_n1neslim.sh

WARNING: Stock Android 7.1.2 system images from the box are usually encrypted
(AES) and WILL NOT work as input. Use Android 9 / LineageOS sources.

ALTERNATIVE WITHOUT WSL: double-click tools\n1neslim-peppermint\bin\n1neslim.jar
(after installing Java 17 on Windows) - it opens the same interactive menu.


================================================================================
STEP 5 - GET A CUSTOM RECOVERY ON THE BOX (required before flashing anything)
================================================================================
The stock 7.1.2 recovery CANNOT install our zip. You need TWRP/Omni first:

1. On Windows: install "Amlogic USB Burning Tool", download a p281 TWRP/Omni
   .img made for S905W.
2. Put the img into USB Burning Tool, check ONLY "recovery" in the partition
   list, click Start.
3. Power off the box. Insert a paperclip into the AV-jack reset hole (or the
   hole marked Reset), hold it, plug in USB-A male -> USB-A male cable to the
   PC. Release after ~5 s when it says "Connect success".
4. Click Stop, unplug, put the cover back on. Boot holding the reset again to
   enter recovery, or from Android: reboot recovery.


================================================================================
STEP 6 - FLASH YOUR ZIP
================================================================================
1. Copy out/n1neslim_v1_ota.zip onto a FAT32 USB stick.
2. Plug into the box, boot into TWRP (Recovery).
3. Wipe -> Format Data -> type yes   (needed once before first Android 9 flash)
4. Install -> pick the zip -> Swipe to Confirm Flash.
5. When it prints "n1neslim peppermint installed" -> Reboot System.
   First boot takes several minutes. Be patient.

No HDMI remote handy? From a PC with adb:
    adb reboot recovery
    adb sideload n1neslim_v1_ota.zip


================================================================================
FOLDER MAP (what everything in this kit is)
================================================================================
README_START_HERE.txt ........... you are here
device_config.ini ............... tells the builder which partitions to write
                                  (already correct for p281/S905W - don't edit)
demo/system.img, demo/boot.img .. tiny FAKE images for testing the pipeline
keys/testkey.pk8 + .x509.pem .... AOSP test signing keys (dev use only)
out/n1neslim_v1_ota.zip ......... pre-built example output (verified OK)
tools/n1neslim-peppermint/
    build_n1neslim.sh ........... the main script (also has a menu: add "menu")
    bin/n1neslim.jar ............ the app itself (works on Windows too)
    src/n1neslim/*.java ......... full source code of the app
    build/classes/ .............. compiled pieces (ignore)

Useful commands (run inside ~/n1neslim):
    ./tools/n1neslim-peppermint/build_n1neslim.sh menu       <- interactive menu
    ./tools/n1neslim-peppermint/build_n1neslim.sh skeleton   <- structure-only zip
    java -jar tools/n1neslim-peppermint/bin/n1neslim.jar help<- all subcommands


================================================================================
TROUBLE - 9 THINGS THAT GO WRONG AND THE FIX
================================================================================
"javac/java not found"        -> redo STEP 2 (sudo apt install openjdk-17...)
"Permission denied"           -> chmod +x tools/n1neslim-peppermint/build_n1neslim.sh
"brotli: command not found"   -> sudo apt install -y brotli   (tool falls back to
                                 gzip automatically, so builds still succeed)
"cannot create ... read-only" -> you ran from /mnt/c; copy folder to ~ first
                                 (Step 3.2) and retry
"TWRP: this package is for..."-> device mismatch; verify ro.product.device=p281
                                 in the source image you used
"E30 Format ..." in TWRP      -> do Wipe -> Format Data -> type yes, retry
"signature verification fail" -> flash via "Install Image" is wrong; use
                                 Install (zip). Or regenerate keys:
                                 java -jar tools/n1neslim-peppermint/bin/n1neslim.jar genkeys
"box boots to black screen"   -> boot.img didn't match system; reflash stock
                                 with USB Burning Tool, start over
"unzip -l fails"              -> sudo apt install -y unzip


================================================================================
QUICK RECAP - the shortest possible path
================================================================================
  wsl --install                       (PowerShell, admin, restart)
  sudo apt update && sudo apt install -y openjdk-17-jdk-headless brotli zip unzip openssl
  cp -r /mnt/c/.../n1neslim_peppermint_kit ~/n1neslim && cd ~/n1neslim
  chmod +x tools/n1neslim-peppermint/build_n1neslim.sh
  ./tools/n1neslim-peppermint/build_n1neslim.sh
  -> out/n1neslim_v1_ota.zip  = your OS package. Done.

Have fun. - team n1neslim "peppermint"
================================================================================
