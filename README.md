# TempRoot VPN Tether

Compartilha a VPN ativa (tun/wg/ppp) via Hotspot ou USB tethering com NAT (iptables + ip rule, tabela 99).

## Compilar
Abra a pasta no Android Studio (Koala+, JDK 17). Ele gera o Gradle wrapper e sincroniza sozinho.

## Uso
1. Obtenha root temporário (KernelSU late-load etc.) e ligue a VPN.
2. Ligue o Hotspot (ou USB tethering).
3. Abra o app -> Verificar -> Ativar.
Se o APK não conseguir chamar `su`: aba "Modo Ponte Local".

## Observações
- `ip_forward` não é revertido ao desativar (o tethering do Android precisa dele).
- IPv6 não é tratado: clientes com IPv6 podem vazar fora da VPN. Desative IPv6 no hotspot/VPN se isso importar.
- MSS clamp e regras são removidos com -D na ordem inversa; o estado fica salvo no DataStore para limpar mesmo após o app ser morto.
