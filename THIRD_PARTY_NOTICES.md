# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA DLSS / NGX SDK

Caustica can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Caustica's `ngxshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## Optional Streamline backend

The opt-in `withStreamline` build profile includes NVIDIA Streamline SDK 2.14.1
runtime components in `caustica/streamline-natives/windows-x64/`, independently
of the original NGX runtime bundle. The SDK MIT license and third-party notices
are included in `META-INF/streamline/`; the DLSS and Reflex notices accompany
their DLLs. NVIDIA component terms remain separate from this project's license.

Project-owned Streamline bridge/controller code was adapted from the local
IterationRTX reference renderer. SDK source: https://github.com/NVIDIA-RTX/Streamline/tree/v2.14.1

The community RTXMFG DLL is **not redistributed**. The optional bootstrap loads
only the user-installed, explicitly enabled v1.4.2 asset from the current game
instance's `native-experiments` directory after checking its pinned SHA-256.
Upstream: https://github.com/dashdogy/RTX40MFG-Unlock/tree/v1.4.2

## Shader pack noise textures (user-supplied, never distributed)

The clouds and the End sky can optionally use two 64×64 noise textures from shader packs the user already owns:
`PTGI_BlueNoise.png` from SEUS PTGI and `noise.png` from ITRP. Neither this repository, the published jars, nor
the installer contains them. The installer copies them out of the user's own shader pack archives into the jar it
builds on the user's machine (`assets/caustica/external/`); otherwise Caustica reads them from an ITRP archive in
the user's `shaderpacks` folder, and if neither is present it uses its own procedurally generated noise. The
textures remain the property of their original authors and are not covered by the LGPL. Please support the
original shader packs.
