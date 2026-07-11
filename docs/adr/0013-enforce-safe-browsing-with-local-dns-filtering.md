# Enforce safe browsing with local DNS filtering

Production Safe Browsing will use Android VpnService to filter DNS locally on the Child Device, rather than reading supported browser address bars through Accessibility. This provides broader, browser-independent domain enforcement without operating a remote VPN gateway or uploading browsing traffic, while accepting Android's visible VPN indicator, Play declaration requirements, and incompatibility with another active VPN.
