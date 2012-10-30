#!/bin/bash
ver=1.0
ver2=$ver
rm lib/DynmapCore-*.jar
cp ~/DynmapCore/target/DynmapCore-$ver.jar lib
cp ~/DynmapCoreAPI/target/DynmapCoreAPI-$ver.jar lib
./recompile.sh
./reobfuscate.sh
rm -r -f zip
mkdir zip
cd zip
unzip ../lib/DynmapCore-$ver.jar
rm -r -f META-INF
unzip ../lib/DynmapCoreAPI-$ver.jar
rm -r -f META-INF
mkdir -p org/dynmap/forge
cp ../reobf/minecraft/org/dynmap/forge/* org/dynmap/forge
cp ../configuration.txt .
rm ../Dynmap-$ver2.zip
zip -r ../Dynmap-$ver2.zip *
cd ..
rm -r -f zip2
mkdir zip2
cd zip2
unzip ~/dynmap/target/dynmap-$ver-bin.zip
rm dynmap.jar
mkdir mods
cp ../Dynmap-$ver2.zip mods
echo "var dynmapversion = \"$ver2\";" > dynmap/web/version.js
rm ../dynmap-$ver2-forge-4.2.5.zip
zip -r ../dynmap-$ver2-forge-4.2.5.zip *
cd ..
 
