// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {ERC20} from "./ERC20/ERC20.sol";


contract ISTCoin is ERC20 {

    constructor(uint256 initialSupply) ERC20("IST Coin", "IST") {
        _mint(msg.sender, initialSupply * 10 ** decimals());
    }

    /**
     * Overridden decimals to 2.
     */
    function decimals() public view virtual override returns (uint8) {
        return 2;
    }

    /**
     * Standard approve function restricted to protect legacy clients.
     * Prevents transitioning from a non-zero allowance to another non-zero value.
     */
    function approve(address spender, uint256 value) public virtual override returns (bool) {
        address owner = _msgSender();
        uint256 currentAllowance = allowance(owner, spender);
        
        // Anti-frontrunning check for absolute values.
        require(value == 0 || currentAllowance == 0, "ERC20: approve from non-zero to non-zero allowance");
        
        _approve(owner, spender, value);
        return true;
    }

    /**
     * Atomically increases the allowance granted to `spender` by the caller.
     */
    function increaseAllowance(address spender, uint256 addedValue) public virtual returns (bool) {
        address owner = _msgSender();
        _approve(owner, spender, allowance(owner, spender) + addedValue);
        return true;
    }

    /**
     * Atomically decreases the allowance granted to `spender` by the caller.
     */
    function decreaseAllowance(address spender, uint256 subtractedValue) public virtual returns (bool) {
        address owner = _msgSender();
        uint256 currentAllowance = allowance(owner, spender);
        require(currentAllowance >= subtractedValue, "ERC20: decreased allowance below zero");
        unchecked {
            _approve(owner, spender, currentAllowance - subtractedValue);
        }
        return true;
    }
}
